package com.example.sdr2hdr

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws the decoder's external texture through: 3D LUT -> SDR-to-HLG conversion.
 * Must be created and used on the thread whose EGL context is current.
 */
class GlRenderer(lut: CubeLut?) {

    val oesTextureId: Int
    private val program: Int
    private val lutTexture: Int
    private val lutSize: Int

    private val aPosition: Int
    private val aTexCoord: Int
    private val uTexMatrix: Int
    private val uTexSampler: Int
    private val uLut: Int
    private val uLutSize: Int
    private val uLutStrength: Int
    private val uExpand: Int
    private val uMode: Int

    private val hasLut = lut != null

    private val vertices: FloatBuffer = floatBuffer(
        floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    )
    private val texCoords: FloatBuffer = floatBuffer(
        floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    )

    init {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES30.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES30.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES30.glGetUniformLocation(program, "uTexMatrix")
        uTexSampler = GLES30.glGetUniformLocation(program, "uTexSampler")
        uLut = GLES30.glGetUniformLocation(program, "uLut")
        uLutSize = GLES30.glGetUniformLocation(program, "uLutSize")
        uLutStrength = GLES30.glGetUniformLocation(program, "uLutStrength")
        uExpand = GLES30.glGetUniformLocation(program, "uExpand")
        uMode = GLES30.glGetUniformLocation(program, "uMode")

        // External texture the decoder writes into (via SurfaceTexture)
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        oesTextureId = tex[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        // 3D LUT texture (an identity 2x2x2 cube stands in when no LUT is supplied)
        val effective = lut ?: identityLut()
        lutSize = effective.size
        val lt = IntArray(1)
        GLES30.glGenTextures(1, lt, 0)
        lutTexture = lt[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        // RGB16F is filterable in core ES 3.0; RGB32F is not without an extension.
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F,
            lutSize, lutSize, lutSize, 0,
            GLES30.GL_RGB, GLES30.GL_FLOAT, floatBuffer(effective.data)
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
    }

    fun draw(
        texMatrix: FloatArray,
        width: Int,
        height: Int,
        lutStrength: Float,
        expand: Float,
        mode: Int
    ) {
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        GLES30.glUseProgram(program)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glUniform1i(uTexSampler, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        GLES30.glUniform1i(uLut, 1)

        GLES30.glUniform1f(uLutSize, lutSize.toFloat())
        GLES30.glUniform1f(uLutStrength, if (hasLut) lutStrength else 0f)
        GLES30.glUniform1f(uExpand, expand)
        GLES30.glUniform1i(uMode, mode)
        GLES30.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)

        GLES30.glEnableVertexAttribArray(aPosition)
        GLES30.glVertexAttribPointer(aPosition, 2, GLES30.GL_FLOAT, false, 0, vertices)
        GLES30.glEnableVertexAttribArray(aTexCoord)
        GLES30.glVertexAttribPointer(aTexCoord, 2, GLES30.GL_FLOAT, false, 0, texCoords)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(aPosition)
        GLES30.glDisableVertexAttribArray(aTexCoord)
    }

    fun release() {
        GLES30.glDeleteProgram(program)
        GLES30.glDeleteTextures(2, intArrayOf(oesTextureId, lutTexture), 0)
    }

    private fun identityLut(): CubeLut {
        val n = 2
        val data = FloatArray(n * n * n * 3)
        var i = 0
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            data[i++] = r.toFloat()
            data[i++] = g.toFloat()
            data[i++] = b.toFloat()
        }
        return CubeLut(n, data)
    }

    private companion object {
        fun floatBuffer(array: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(array.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(array)
                .also { it.position(0) }

        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader) }
            return shader
        }

        fun createProgram(vertex: String, fragment: String): Int {
            val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "Program link failed: " + GLES20.glGetProgramInfoLog(program) }
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            return program
        }

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

        // Reads the decoder external texture; SDR / HLG / PQ output by uMode.
        val FRAGMENT_SHADER = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            precision highp sampler3D;

            uniform samplerExternalOES uTexSampler;
            uniform sampler3D uLut;
            uniform float uLutSize;
            uniform float uLutStrength;
            uniform float uExpand;
            uniform int uMode;   // 0 = SDR BT.709, 1 = HLG BT.2020, 2 = HDR10 PQ BT.2020

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

            // SMPTE ST 2084 inverse EOTF. y = display light / 10000 nits.
            float pqOetf(float y) {
                const float m1 = 0.1593017578125;
                const float m2 = 78.84375;
                const float c1 = 0.8359375;
                const float c2 = 18.8515625;
                const float c3 = 18.6875;
                float p = pow(max(y, 0.0), m1);
                return pow((c1 + c2 * p) / (1.0 + c3 * p), m2);
            }

            vec3 applyLut(vec3 rgb) {
                vec3 scale = vec3((uLutSize - 1.0) / uLutSize);
                vec3 offset = vec3(0.5 / uLutSize);
                return texture(uLut, clamp(rgb, 0.0, 1.0) * scale + offset).rgb;
            }

            void main() {
                vec3 src = texture(uTexSampler, vTexSamplingCoord).rgb;
                vec3 graded = mix(src, applyLut(src), uLutStrength);

                if (uMode == 0) {                      // SDR: LUT only
                    outColor = vec4(clamp(graded, 0.0, 1.0), 1.0);
                    return;
                }

                vec3 lin = pow(max(graded, 0.0), vec3(2.4));
                float luma = dot(lin, vec3(0.2627, 0.6780, 0.0593));
                lin *= 1.0 + uExpand * smoothstep(0.5, 1.0, luma);
                vec3 lin2020 = max(BT709_TO_BT2020 * lin, 0.0);

                if (uMode == 1) {                      // HLG: SDR white at 75% signal
                    vec3 s = lin2020 * 0.265;
                    outColor = vec4(hlgOetf(s.r), hlgOetf(s.g), hlgOetf(s.b), 1.0);
                } else {                               // PQ: SDR white at 203 nits
                    vec3 y = lin2020 * (203.0 / 10000.0);
                    outColor = vec4(pqOetf(y.r), pqOetf(y.g), pqOetf(y.b), 1.0);
                }
            }
        """.trimIndent()
    }
}
