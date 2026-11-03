package com.example.sdr2hdr

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * SDR (BT.709) video -> LUT / sharpen / HDR look -> HDR10 (PQ), HLG or SDR output,
 * 10-bit HEVC MP4, with resolution change (up or down) and frame-rate change
 * (drop frames, or 2x with optical-flow interpolation).
 *
 * transcode() blocks, so call it from a background thread.
 */
class SdrToHdrTranscoder(private val context: Context) {

    data class Options(
        val lut: CubeLut? = null,
        val lutStrength: Float = 1f,
        val space: OutputSpace = OutputSpace.HDR10_PQ,
        val whiteNits: Float = 400f,        // brightness of SDR white in HDR output (203 = reference)
        val hdrEffect: Float = 0.3f,        // 0..1 highlight boost
        val sharpen: Float = 0f,            // 0..~1.5
        val targetShortSide: Int? = null,   // e.g. 1080 or 2160; null = keep source size
        val targetFps: Int? = null,         // lower than source only (frames dropped); null = keep
        val interpolate2x: Boolean = false, // double the frame rate with optical flow
        val bitrate: Int? = null            // bits/sec; null = estimated
    )

    data class SourceInfo(
        val width: Int,
        val height: Int,
        val fps: Int,
        val rotation: Int,
        val durationUs: Long,
        val isHdr: Boolean
    )

    companion object {
        fun probe(context: Context, uri: Uri): SourceInfo {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(context, uri, null)
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (!mime.startsWith("video/")) continue
                    val transfer = f.getInteger(MediaFormat.KEY_COLOR_TRANSFER, 0)
                    return SourceInfo(
                        width = f.getInteger(MediaFormat.KEY_WIDTH),
                        height = f.getInteger(MediaFormat.KEY_HEIGHT),
                        fps = runCatching { f.getNumber(MediaFormat.KEY_FRAME_RATE)?.toDouble()?.roundToInt() }
                            .getOrNull()?.takeIf { it > 0 } ?: 30,
                        rotation = f.getInteger(MediaFormat.KEY_ROTATION, 0),
                        durationUs = if (f.containsKey(MediaFormat.KEY_DURATION))
                            f.getLong(MediaFormat.KEY_DURATION) else 0L,
                        isHdr = transfer == MediaFormat.COLOR_TRANSFER_ST2084 ||
                            transfer == MediaFormat.COLOR_TRANSFER_HLG
                    )
                }
                error("No video track found")
            } finally {
                ex.release()
            }
        }

        private fun evenSize(v: Double): Int = (((v.roundToInt()) / 2) * 2).coerceAtLeast(2)

        /** CTA-861.3 static metadata (type 1), same mastering values as the reference HDR10 clip. */
        private fun hdr10StaticInfo(): ByteBuffer {
            val b = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN)
            b.put(0)
            intArrayOf(
                34000, 16000, 13250, 34500, 7500, 3000,   // R, G, B primaries (0.00002 units)
                15635, 16450,                              // white point D65
                4000, 10,                                  // max / min mastering luminance
                0, 0                                       // MaxCLL, MaxFALL (unspecified)
            ).forEach { b.putShort(it.toShort()) }
            b.flip()
            return b
        }
    }

    fun transcode(
        input: Uri,
        outputPath: String,
        options: Options = Options(),
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ) {
        require(Build.VERSION.SDK_INT >= 33) {
            "10-bit HDR surface encoding needs Android 13 (API 33) or newer"
        }

        val extractor = MediaExtractor()
        // Nullable handles are only for cleanup in finally; the body uses non-null vals.
        var decoderH: MediaCodec? = null
        var encoderH: MediaCodec? = null
        var eglH: EglCore? = null
        var rendererH: GlRenderer? = null
        var surfaceTextureH: SurfaceTexture? = null
        var decoderSurfaceH: Surface? = null
        var muxerH: MediaMuxer? = null
        var muxerStarted = false
        val frameThread = HandlerThread("frame-callbacks").also { it.start() }

        try {
            extractor.setDataSource(context, input, null)

            var videoTrack = -1
            var audioTrack = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (videoTrack < 0 && mime.startsWith("video/")) videoTrack = i
                else if (audioTrack < 0 && mime == MediaFormat.MIMETYPE_AUDIO_AAC) audioTrack = i
            }
            require(videoTrack >= 0) { "No video track found" }
            extractor.selectTrack(videoTrack)

            val inFormat = extractor.getTrackFormat(videoTrack)
            val src = probe(context, input)
            require(!src.isHdr) { "Input is already HDR; pick an SDR video" }

            val width = src.width
            val height = src.height
            val rotation = src.rotation
            val durationUs = src.durationUs
            val srcFps = src.fps
            val space = options.space

            // ---- Output size and frame rate ------------------------------------------------
            val scale = options.targetShortSide?.takeIf { it > 0 }
                ?.let { it.toDouble() / min(width, height) } ?: 1.0
            val outW = evenSize(width * scale)
            val outH = evenSize(height * scale)

            val interpolate = options.interpolate2x
            val outFps = when {
                interpolate -> srcFps * 2
                else -> options.targetFps?.takeIf { it in 1 until srcFps } ?: srcFps
            }
            val keepAll = interpolate || outFps >= srcFps
            val intervalUs = 1_000_000.0 / outFps
            val srcIntervalUs = 1_000_000.0 / srcFps
            var nextKeepUs = -1.0
            fun shouldKeep(ptsUs: Long): Boolean {
                if (keepAll) return true
                if (nextKeepUs < 0) { nextKeepUs = ptsUs + intervalUs; return true }
                if (ptsUs >= nextKeepUs - srcIntervalUs / 2) {
                    nextKeepUs += intervalUs
                    if (ptsUs > nextKeepUs) nextKeepUs = ptsUs + intervalUs
                    return true
                }
                return false
            }

            val bitrate = options.bitrate
                ?: (outW.toLong() * outH * outFps * 0.12).toInt().coerceIn(2_000_000, 100_000_000)

            inFormat.setInteger(MediaFormat.KEY_ROTATION, 0)

            // ---- Encoder -------------------------------------------------------------------
            val outFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_HEVC, outW, outH
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, outFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                when (space) {
                    OutputSpace.HDR10_PQ -> {
                        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
                        setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, hdr10StaticInfo())
                    }
                    OutputSpace.HLG -> {
                        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                    }
                    OutputSpace.SDR_709 -> {
                        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                    }
                }
            }
            val encoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(outFormat)
            checkNotNull(encoderName) {
                "This device has no HEVC Main10 encoder for ${outW}x$outH at $outFps fps"
            }
            val enc = MediaCodec.createByCodecName(encoderName)
            encoderH = enc
            enc.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderInputSurface = enc.createInputSurface()

            // ---- GL ------------------------------------------------------------------------
            val eglCore = EglCore()
            eglH = eglCore
            eglCore.createWindowSurface(encoderInputSurface, space)
            eglCore.makeCurrent()
            val gl = GlRenderer(options.lut, width, height, interpolate)
            rendererH = gl
            val params = RenderParams(
                lutStrength = options.lutStrength,
                mode = space.shaderMode,
                whiteNits = options.whiteNits,
                hdrEffect = options.hdrEffect,
                sharpen = options.sharpen
            )

            // ---- Decoder -> SurfaceTexture -------------------------------------------------
            val frameAvailable = Semaphore(0)
            val st = SurfaceTexture(gl.oesTextureId)
            surfaceTextureH = st
            st.setOnFrameAvailableListener({ frameAvailable.release() }, Handler(frameThread.looper))
            val decoderSurface = Surface(st)
            decoderSurfaceH = decoderSurface

            val dec = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
            decoderH = dec
            dec.configure(inFormat, decoderSurface, null, 0)

            // ---- Muxer ---------------------------------------------------------------------
            val mux = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxerH = mux
            mux.setOrientationHint(rotation)
            var muxVideoTrack = -1
            var muxAudioTrack = -1

            dec.start()
            enc.start()

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            val texMatrix = FloatArray(16)
            var inputDone = false
            var decoderDone = false
            var encoderDone = false
            var lastPtsUs = -1L

            fun drainEncoder(timeoutUs: Long) {
                while (true) {
                    val idx = enc.dequeueOutputBuffer(encInfo, timeoutUs)
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) return
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        check(!muxerStarted) { "Format changed twice" }
                        muxVideoTrack = mux.addTrack(enc.outputFormat)
                        if (audioTrack >= 0) {
                            muxAudioTrack = runCatching {
                                mux.addTrack(extractor.getTrackFormat(audioTrack))
                            }.getOrDefault(-1)
                        }
                        mux.start()
                        muxerStarted = true
                        continue
                    }
                    if (idx >= 0) {
                        val data = enc.getOutputBuffer(idx)!!
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encInfo.size = 0
                        if (encInfo.size > 0 && muxerStarted) {
                            data.position(encInfo.offset)
                            data.limit(encInfo.offset + encInfo.size)
                            mux.writeSampleData(muxVideoTrack, data, encInfo)
                        }
                        enc.releaseOutputBuffer(idx, false)
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encoderDone = true
                            return
                        }
                    }
                }
            }

            fun submit(interpolated: Boolean, ptsUs: Long) {
                gl.drawOutput(outW, outH, params, interpolated)
                eglCore.setPresentationTimeNanos(ptsUs * 1000L)
                eglCore.swapBuffers()
                drainEncoder(0)   // keep the encoder moving between the two frames of a pair
            }

            while (!encoderDone) {
                if (isCancelled()) throw CancellationException("Cancelled")

                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(0)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                if (!decoderDone) {
                    val outIdx = dec.dequeueOutputBuffer(decInfo, 10_000)
                    if (outIdx >= 0) {
                        val ptsUs = decInfo.presentationTimeUs
                        val keep = decInfo.size > 0 && shouldKeep(ptsUs)
                        dec.releaseOutputBuffer(outIdx, keep)
                        if (keep) {
                            check(frameAvailable.tryAcquire(2, TimeUnit.SECONDS)) { "Decoder frame timeout" }
                            st.updateTexImage()
                            st.getTransformMatrix(texMatrix)
                            gl.ingest(texMatrix)

                            if (interpolate && gl.hasPrev && ptsUs - lastPtsUs >= 2) {
                                gl.estimateFlow()
                                submit(true, (lastPtsUs + ptsUs) / 2)
                            }
                            submit(false, ptsUs)
                            lastPtsUs = ptsUs

                            if (durationUs > 0) {
                                onProgress((ptsUs.toFloat() / durationUs).coerceIn(0f, 1f))
                            }
                        }
                        if (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            decoderDone = true
                            enc.signalEndOfInputStream()
                        }
                    }
                }

                drainEncoder(if (decoderDone) 10_000L else 0L)
            }

            if (muxerStarted && muxAudioTrack >= 0 && audioTrack >= 0) {
                copyAudio(input, audioTrack, mux, muxAudioTrack)
            }
            onProgress(1f)
        } finally {
            runCatching { decoderH?.stop() }
            runCatching { decoderH?.release() }
            runCatching { encoderH?.stop() }
            runCatching { encoderH?.release() }
            runCatching { surfaceTextureH?.release() }
            runCatching { decoderSurfaceH?.release() }
            runCatching { rendererH?.release() }
            runCatching { eglH?.release() }
            if (muxerStarted) runCatching { muxerH?.stop() }
            runCatching { muxerH?.release() }
            runCatching { extractor.release() }
            frameThread.quitSafely()
        }
    }

    private fun copyAudio(input: Uri, trackIndex: Int, muxer: MediaMuxer, muxTrack: Int) {
        val audio = MediaExtractor()
        try {
            audio.setDataSource(context, input, null)
            audio.selectTrack(trackIndex)
            val format = audio.getTrackFormat(trackIndex)
            val maxSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 256 * 1024
            val buffer = ByteBuffer.allocateDirect(maxSize)
            val info = MediaCodec.BufferInfo()
            while (true) {
                buffer.clear()
                val size = audio.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(
                    0, size, audio.sampleTime,
                    if (audio.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                        MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                )
                muxer.writeSampleData(muxTrack, buffer, info)
                audio.advance()
            }
        } finally {
            audio.release()
        }
    }
}
