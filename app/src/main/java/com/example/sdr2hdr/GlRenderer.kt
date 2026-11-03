package com.example.sdr2hdr

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Per-frame look parameters passed to the final shader pass. */
class RenderParams(
    val lutStrength: Float,
    val mode: Int,          // OutputSpace.shaderMode
    val whiteNits: Float,   // brightness of SDR white in the HDR output
    val hdrEffect: Float,   // 0..1 highlight boost + saturation
    val sharpen: Float      // 0..~1.5 unsharp amount
)

/**
 * Multi-pass GPU pipeline (OpenGL ES 3.0). Must be created and used on the thread
 * whose EGL context is current.
 *
 *  ingest():       decoder external texture -> RGBA8 frame texture (+ luma pyramid)
 *  estimateFlow(): coarse-to-fine block-matching optical flow, previous -> current frame
 *  drawOutput():   [optional flow-warped mid-frame] -> sharpen -> LUT -> SDR/HLG/PQ -> encoder surface
 */
class GlRenderer(
    lut: CubeLut?,
    private val srcW: Int,
    private val srcH: Int,
    private val withFlow: Boolean
) {
    val oesTextureId: Int

    private val vertices: FloatBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val texCoords: FloatBuffer = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private val blitProg: Program
    private val downProg: Program
    private val flowProg: Program?
    private val mainProg: Program

    private val frames: Array<FrameSet>
    private val flows: List<Target>
    private var cur = 1
    private var count = 0

    private val lutTexture: Int
    private val lutSize: Int
    private val hasLut = lut != null

    /** True once two frames have been ingested, so flow can be estimated. */
    val hasPrev: Boolean get() = count >= 2

    init {
        if (withFlow) {
            val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
            check(ext.contains("GL_EXT_color_buffer_float") || ext.contains("GL_EXT_color_buffer_half_float")) {
                "This GPU cannot render to float textures, so optical-flow frame interpolation is unavailable"
            }
        }

        blitProg = Program(VERTEX_SHADER, BLIT_FRAGMENT)
        downProg = Program(VERTEX_SHADER, DOWN_FRAGMENT)
        flowProg = if (withFlow) Program(VERTEX_SHADER, FLOW_FRAGMENT) else null
        mainProg = Program(VERTEX_SHADER, MAIN_FRAGMENT)

        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        oesTextureId = tex[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val levels = if (withFlow) LEVELS else 0
        frames = arrayOf(FrameSet(srcW, srcH, levels), FrameSet(srcW, srcH, levels))
        flows = (0 until levels).map { l ->
            Target(
                maxOf(2, srcW shr (l + 1)), maxOf(2, srcH shr (l + 1)),
                GLES30.GL_RGBA16F, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT
            )
        }

        // 3D LUT (identity 2x2x2 when none supplied)
        val effective = lut ?: identityLut()
        lutSize = effective.size
        val lt = IntArray(1)
        GLES30.glGenTextures(1, lt, 0)
        lutTexture = lt[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F,
            lutSize, lutSize, lutSize, 0,
            GLES30.GL_RGB, GLES30.GL_FLOAT, floatBuffer(effective.data)
        )
        for (p in intArrayOf(GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_TEXTURE_MAG_FILTER))
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, p, GLES30.GL_LINEAR)
        for (p in intArrayOf(GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_TEXTURE_WRAP_R))
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, p, GLES30.GL_CLAMP_TO_EDGE)
    }

    /** Copies the newest decoder frame into our own texture (and builds its luma pyramid). */
    fun ingest(texMatrix: FloatArray) {
        cur = 1 - cur
        val f = frames[cur]

        f.frame.bindForDraw()
        GLES30.glUseProgram(blitProg.id)
        bindTex(0, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId, blitProg.u("uTex"))
        drawQuad(blitProg, texMatrix)

        if (withFlow) {
            GLES30.glUseProgram(downProg.id)
            for (l in f.pyr.indices) {
                f.pyr[l].bindForDraw()
                val src = if (l == 0) f.frame.tex else f.pyr[l - 1].tex
                bindTex(0, GLES30.GL_TEXTURE_2D, src, downProg.u("uSrc"))
                GLES30.glUniform1i(downProg.u("uFromRgb"), if (l == 0) 1 else 0)
                drawQuad(downProg, identity)
            }
        }
        count++
    }

    /** Block-matching flow, coarse to fine. flows[0] ends up holding previous->current motion. */
    fun estimateFlow() {
        check(withFlow && hasPrev)
        val fp = flowProg!!
        val c = frames[cur]
        val p = frames[1 - cur]
        GLES30.glUseProgram(fp.id)
        for (l in LEVELS - 1 downTo 0) {
            val t = flows[l]
            t.bindForDraw()
            bindTex(0, GLES30.GL_TEXTURE_2D, c.pyr[l].tex, fp.u("uCur"))
            bindTex(1, GLES30.GL_TEXTURE_2D, p.pyr[l].tex, fp.u("uPrev"))
            val hasParent = l < LEVELS - 1
            bindTex(2, GLES30.GL_TEXTURE_2D, if (hasParent) flows[l + 1].tex else p.pyr[l].tex, fp.u("uParent"))
            GLES30.glUniform1i(fp.u("uHasParent"), if (hasParent) 1 else 0)
            GLES30.glUniform2f(fp.u("uSize"), t.w.toFloat(), t.h.toFloat())
            GLES30.glUniform1i(fp.u("uRadius"), if (l == LEVELS - 1) 4 else 2)
            drawQuad(fp, identity)
        }
    }

    /** Draws to the encoder surface. interpolated = true renders the half-way frame. */
    fun drawOutput(outW: Int, outH: Int, p: RenderParams, interpolated: Boolean) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, outW, outH)
        GLES30.glUseProgram(mainProg.id)

        val curTex = frames[cur].frame.tex
        val prevTex = frames[1 - cur].frame.tex
        bindTex(0, GLES30.GL_TEXTURE_2D, curTex, mainProg.u("uCur"))
        bindTex(1, GLES30.GL_TEXTURE_2D, prevTex, mainProg.u("uPrev"))
        bindTex(2, GLES30.GL_TEXTURE_2D, if (flows.isNotEmpty()) flows[0].tex else curTex, mainProg.u("uFlow"))
        bindTex(3, GLES30.GL_TEXTURE_3D, lutTexture, mainProg.u("uLut"))

        GLES30.glUniform1f(mainProg.u("uLutSize"), lutSize.toFloat())
        GLES30.glUniform1f(mainProg.u("uLutStrength"), if (hasLut) p.lutStrength else 0f)
        GLES30.glUniform1i(mainProg.u("uMode"), p.mode)
        GLES30.glUniform1f(mainProg.u("uWhiteNits"), p.whiteNits)
        GLES30.glUniform1f(mainProg.u("uHdrEffect"), p.hdrEffect)
        GLES30.glUniform1f(mainProg.u("uSharpen"), p.sharpen)
        GLES30.glUniform1i(mainProg.u("uInterp"), if (interpolated) 1 else 0)
        GLES30.glUniform1i(mainProg.u("uBicubic"), if (outW > srcW || outH > srcH) 1 else 0)
        GLES30.glUniform2f(mainProg.u("uTexel"), 1f / outW, 1f / outH)
        drawQuad(mainProg, identity)
    }

    fun release() {
        blitProg.release(); downProg.release(); flowProg?.release(); mainProg.release()
        frames.forEach { it.release() }
        flows.forEach { it.release() }
        GLES30.glDeleteTextures(2, intArrayOf(oesTextureId, lutTexture), 0)
    }

    private fun drawQuad(p: Program, texMatrix: FloatArray) {
        GLES30.glUniformMatrix4fv(p.u("uTexMatrix"), 1, false, texMatrix, 0)
        val ap = p.a("aPosition")
        val at = p.a("aTexCoord")
        GLES30.glEnableVertexAttribArray(ap)
        GLES30.glVertexAttribPointer(ap, 2, GLES30.GL_FLOAT, false, 0, vertices)
        GLES30.glEnableVertexAttribArray(at)
        GLES30.glVertexAttribPointer(at, 2, GLES30.GL_FLOAT, false, 0, texCoords)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(ap)
        GLES30.glDisableVertexAttribArray(at)
    }

    private fun bindTex(unit: Int, target: Int, tex: Int, uniform: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(target, tex)
        GLES30.glUniform1i(uniform, unit)
    }

    private fun identityLut(): CubeLut {
        val n = 2
        val data = FloatArray(n * n * n * 3)
        var i = 0
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            data[i++] = r.toFloat(); data[i++] = g.toFloat(); data[i++] = b.toFloat()
        }
        return CubeLut(n, data)
    }

    private companion object {
        const val LEVELS = 4   // flow pyramid: 1/2, 1/4, 1/8, 1/16 of source size

        val VERTEX_SHADER = """
            #version 300 es
            in vec4 aPosition;
            in vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            out vec2 vTexSamplingCoord;
            void main() {
                gl_Position = aPosition;
                vTexSamplingCoord = (uTexMatrix * aTexCoord).xy;
            }
        """.trimIndent()

        val BLIT_FRAGMENT = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            precision highp samplerExternalOES;
            uniform samplerExternalOES uTex;
            in vec2 vTexSamplingCoord;
            out vec4 outColor;
            void main() {
                outColor = vec4(texture(uTex, vTexSamplingCoord).rgb, 1.0);
            }
        """.trimIndent()

        val DOWN_FRAGMENT = """
            #version 300 es
            precision highp float;
            precision highp sampler2D;
            uniform sampler2D uSrc;
            uniform int uFromRgb;
            in vec2 vTexSamplingCoord;
            out vec4 outColor;
            void main() {
                vec3 c = texture(uSrc, vTexSamplingCoord).rgb;
                float l = uFromRgb == 1 ? dot(c, vec3(0.299, 0.587, 0.114)) : c.r;
                outColor = vec4(l, l, l, 1.0);
            }
        """.trimIndent()

        // Flow in UV units: prev(uv + flow) ~= cur(uv). 3x3 SAD on luma, search around parent flow.
        val FLOW_FRAGMENT = """
            #version 300 es
            precision highp float;
            precision highp sampler2D;
            uniform sampler2D uCur;
            uniform sampler2D uPrev;
            uniform sampler2D uParent;
            uniform int uHasParent;
            uniform vec2 uSize;
            uniform int uRadius;
            in vec2 vTexSamplingCoord;
            out vec4 outFlow;

            float patchCost(vec2 uv, vec2 off) {
                float sad = 0.0;
                for (int j = -1; j <= 1; j++) {
                    for (int i = -1; i <= 1; i++) {
                        vec2 p = vec2(float(i), float(j)) / uSize;
                        sad += abs(texture(uCur, uv + p).r - texture(uPrev, uv + p + off).r);
                    }
                }
                return sad;
            }

            void main() {
                vec2 uv = vTexSamplingCoord;
                vec2 base = uHasParent == 1 ? texture(uParent, uv).xy : vec2(0.0);
                float best = 1e9;
                vec2 bestOff = base;
                for (int y = -uRadius; y <= uRadius; y++) {
                    for (int x = -uRadius; x <= uRadius; x++) {
                        vec2 local = vec2(float(x), float(y));
                        vec2 off = base + local / uSize;
                        float cost = patchCost(uv, off) + 0.01 * length(local);
                        if (cost < best) { best = cost; bestOff = off; }
                    }
                }
                outFlow = vec4(bestOff, best, 1.0);
            }
        """.trimIndent()

        val MAIN_FRAGMENT = """
            #version 300 es
            precision highp float;
            precision highp sampler2D;
            precision highp sampler3D;

            uniform sampler2D uCur;
            uniform sampler2D uPrev;
            uniform sampler2D uFlow;
            uniform sampler3D uLut;
            uniform float uLutSize;
            uniform float uLutStrength;
            uniform int uMode;          // 0 = SDR BT.709, 1 = HLG BT.2020, 2 = HDR10 PQ BT.2020
            uniform float uWhiteNits;
            uniform float uHdrEffect;
            uniform float uSharpen;
            uniform int uInterp;
            uniform int uBicubic;
            uniform vec2 uTexel;        // 1 / output size

            in vec2 vTexSamplingCoord;
            out vec4 outColor;

            const mat3 BT709_TO_BT2020 = mat3(
                0.6274, 0.0691, 0.0164,
                0.3293, 0.9195, 0.0880,
                0.0433, 0.0114, 0.8956);

            float hlgOetf(float e) {
                const float a = 0.17883277;
                const float b = 0.28466892;
                const float c = 0.55991073;
                return e <= 1.0 / 12.0 ? sqrt(3.0 * e) : a * log(12.0 * e - b) + c;
            }

            float pqOetf(float y) {
                const float m1 = 0.1593017578125;
                const float m2 = 78.84375;
                const float c1 = 0.8359375;
                const float c2 = 18.8515625;
                const float c3 = 18.6875;
                float p = pow(max(y, 0.0), m1);
                return pow((c1 + c2 * p) / (1.0 + c3 * p), m2);
            }

            // Catmull-Rom bicubic using 9 bilinear taps (used when upscaling)
            vec3 sampleBicubic(sampler2D tex, vec2 uv) {
                vec2 texSize = vec2(textureSize(tex, 0));
                vec2 samplePos = uv * texSize;
                vec2 texPos1 = floor(samplePos - 0.5) + 0.5;
                vec2 f = samplePos - texPos1;
                vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
                vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
                vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
                vec2 w3 = f * f * (-0.5 + 0.5 * f);
                vec2 w12 = w1 + w2;
                vec2 off12 = w2 / w12;
                vec2 p0 = (texPos1 - 1.0) / texSize;
                vec2 p3 = (texPos1 + 2.0) / texSize;
                vec2 p12 = (texPos1 + off12) / texSize;
                vec3 r = vec3(0.0);
                r += texture(tex, vec2(p0.x, p0.y)).rgb * (w0.x * w0.y);
                r += texture(tex, vec2(p12.x, p0.y)).rgb * (w12.x * w0.y);
                r += texture(tex, vec2(p3.x, p0.y)).rgb * (w3.x * w0.y);
                r += texture(tex, vec2(p0.x, p12.y)).rgb * (w0.x * w12.y);
                r += texture(tex, vec2(p12.x, p12.y)).rgb * (w12.x * w12.y);
                r += texture(tex, vec2(p3.x, p12.y)).rgb * (w3.x * w12.y);
                r += texture(tex, vec2(p0.x, p3.y)).rgb * (w0.x * w3.y);
                r += texture(tex, vec2(p12.x, p3.y)).rgb * (w12.x * w3.y);
                r += texture(tex, vec2(p3.x, p3.y)).rgb * (w3.x * w3.y);
                return clamp(r, 0.0, 1.0);
            }

            vec3 fetch(sampler2D tex, vec2 uv, bool hq) {
                return hq ? sampleBicubic(tex, uv) : texture(tex, uv).rgb;
            }

            // Source colour: the current frame, or the motion-compensated half-way frame.
            vec3 srcColor(vec2 uv, bool hq) {
                if (uInterp == 0) return fetch(uCur, uv, hq);
                vec2 d0 = texture(uFlow, uv).xy;
                vec2 d = texture(uFlow, uv - 0.5 * d0).xy;
                vec3 a = fetch(uPrev, uv + 0.5 * d, hq);
                vec3 b = fetch(uCur, uv - 0.5 * d, hq);
                vec3 plain = 0.5 * (fetch(uPrev, uv, hq) + fetch(uCur, uv, hq));
                float err = length(a - b);
                return mix(0.5 * (a + b), plain, smoothstep(0.10, 0.30, err));
            }

            vec3 applyLut(vec3 rgb) {
                vec3 scale = vec3((uLutSize - 1.0) / uLutSize);
                vec3 offset = vec3(0.5 / uLutSize);
                return texture(uLut, clamp(rgb, 0.0, 1.0) * scale + offset).rgb;
            }

            void main() {
                vec2 uv = vTexSamplingCoord;
                vec3 c = srcColor(uv, uBicubic == 1);

                if (uSharpen > 0.0) {
                    vec3 b = 0.25 * (srcColor(uv + vec2(uTexel.x, 0.0), false)
                                   + srcColor(uv - vec2(uTexel.x, 0.0), false)
                                   + srcColor(uv + vec2(0.0, uTexel.y), false)
                                   + srcColor(uv - vec2(0.0, uTexel.y), false));
                    float detail = dot(c - b, vec3(0.299, 0.587, 0.114));
                    c = clamp(c + uSharpen * detail, 0.0, 1.0);
                }

                vec3 graded = mix(c, applyLut(c), uLutStrength);

                if (uMode == 0) {
                    outColor = vec4(graded, 1.0);
                    return;
                }

                vec3 lin = pow(max(graded, 0.0), vec3(2.4));

                // "HDR effect": boost highlights strongly, midtones barely, plus a little saturation
                float luma = dot(lin, vec3(0.2126, 0.7152, 0.0722));
                lin *= 1.0 + uHdrEffect * 3.0 * pow(clamp(luma, 0.0, 1.0), 2.5);
                float l2 = dot(lin, vec3(0.2126, 0.7152, 0.0722));
                lin = max(mix(vec3(l2), lin, 1.0 + 0.35 * uHdrEffect), 0.0);

                vec3 lin2020 = max(BT709_TO_BT2020 * lin, 0.0);

                if (uMode == 1) {
                    // HLG: SDR white at 75% signal for 203 nits, scaled by the brightness setting
                    vec3 s = min(lin2020 * (0.265 * uWhiteNits / 203.0), 1.0);
                    outColor = vec4(hlgOetf(s.r), hlgOetf(s.g), hlgOetf(s.b), 1.0);
                } else {
                    // PQ: linear 1.0 = uWhiteNits, hard cap at the 4000-nit mastering peak
                    vec3 y = min(lin2020 * uWhiteNits, 4000.0) / 10000.0;
                    outColor = vec4(pqOetf(y.r), pqOetf(y.g), pqOetf(y.b), 1.0);
                }
            }
        """.trimIndent()
    }
}

// ---------------------------------------------------------------------------------------------
// File-private helpers
// ---------------------------------------------------------------------------------------------

private fun floatBuffer(array: FloatArray): FloatBuffer =
    ByteBuffer.allocateDirect(array.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(array)
        .also { it.position(0) }

private fun compileShader(type: Int, source: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)
    val status = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
    check(status[0] != 0) { "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader) }
    return shader
}

private class Program(vertex: String, fragment: String) {
    val id: Int
    private val cache = HashMap<String, Int>()

    init {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment)
        id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vs)
        GLES20.glAttachShader(id, fs)
        GLES20.glLinkProgram(id)
        val status = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { "Program link failed: " + GLES20.glGetProgramInfoLog(id) }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
    }

    fun u(name: String): Int = cache.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }
    fun a(name: String): Int = GLES30.glGetAttribLocation(id, name)
    fun release() = GLES30.glDeleteProgram(id)
}

/** A texture with a framebuffer attached, used as an offscreen render target. */
private class Target(val w: Int, val h: Int, internalFormat: Int, format: Int, type: Int) {
    val tex: Int
    val fbo: Int

    init {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        tex = t[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internalFormat, w, h, 0, format, type, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val f = IntArray(1)
        GLES30.glGenFramebuffers(1, f, 0)
        fbo = f[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0
        )
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
            "Framebuffer incomplete (${w}x$h)"
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    fun bindForDraw() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, w, h)
    }

    fun release() {
        GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
    }
}

/** One decoded frame: full-size RGBA8 copy plus its luma pyramid (for optical flow). */
private class FrameSet(srcW: Int, srcH: Int, levels: Int) {
    val frame = Target(srcW, srcH, GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE)
    val pyr: List<Target> = (0 until levels).map { l ->
        Target(
            maxOf(2, srcW shr (l + 1)), maxOf(2, srcH shr (l + 1)),
            GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE
        )
    }

    fun release() {
        frame.release()
        pyr.forEach { it.release() }
    }
}
