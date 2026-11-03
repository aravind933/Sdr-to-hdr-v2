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
 * SDR (BT.709) video -> optional 3D LUT -> HDR10 (PQ) / HLG / SDR output,
 * 10-bit HEVC in an MP4, with optional resolution and frame-rate reduction.
 *
 * transcode() blocks, so call it from a background thread.
 */
class SdrToHdrTranscoder(private val context: Context) {

    data class Options(
        val lut: CubeLut? = null,
        val lutStrength: Float = 1f,
        val expand: Float = 0.25f,
        val space: OutputSpace = OutputSpace.HDR10_PQ,
        val targetShortSide: Int? = null,   // e.g. 1080; null = keep source size
        val targetFps: Int? = null,         // lower than source only; null = keep source
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
                        fps = runCatching { f.getNumber(MediaFormat.KEY_FRAME_RATE)?.toInt() }
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
                34000, 16000,   // red x,y   (0.00002 units)
                13250, 34500,   // green
                7500, 3000,     // blue
                15635, 16450,   // white point D65
                4000,           // max mastering luminance, cd/m2
                10,             // min mastering luminance, 0.0001 cd/m2
                0, 0            // MaxCLL, MaxFALL (unspecified)
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
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var egl: EglCore? = null
        var renderer: GlRenderer? = null
        var surfaceTexture: SurfaceTexture? = null
        var decoderSurface: Surface? = null
        var muxer: MediaMuxer? = null
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
            val info0 = probe(context, input)
            require(!info0.isHdr) { "Input is already HDR; pick an SDR video" }

            val width = info0.width
            val height = info0.height
            val rotation = info0.rotation
            val durationUs = info0.durationUs
            val srcFps = info0.fps
            val space = options.space

            // ---- Output size and frame rate ------------------------------------------------
            val scale = options.targetShortSide?.takeIf { it > 0 }
                ?.let { it.toDouble() / min(width, height) } ?: 1.0
            val outW = evenSize(width * scale)
            val outH = evenSize(height * scale)
            val outFps = options.targetFps?.takeIf { it in 1 until srcFps } ?: srcFps
            val keepAll = outFps >= srcFps
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
                ?: (outW.toLong() * outH * outFps * 0.12).toInt().coerceIn(2_000_000, 80_000_000)

            inFormat.setInteger(MediaFormat.KEY_ROTATION, 0)

            // ---- Encoder: HEVC Main10 with the chosen colour tags --------------------------
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
            checkNotNull(encoderName) { "This device has no HEVC Main10 encoder for ${outW}x$outH" }

            encoder = MediaCodec.createByCodecName(encoderName)
            encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderInputSurface = encoder.createInputSurface()

            // ---- GL ------------------------------------------------------------------------
            egl = EglCore()
            egl.createWindowSurface(encoderInputSurface, space)
            egl.makeCurrent()
            renderer = GlRenderer(options.lut)

            // ---- Decoder -> SurfaceTexture -------------------------------------------------
            val frameAvailable = Semaphore(0)
            surfaceTexture = SurfaceTexture(renderer.oesTextureId)
            surfaceTexture.setOnFrameAvailableListener({ frameAvailable.release() }, Handler(frameThread.looper))
            decoderSurface = Surface(surfaceTexture)

            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inFormat, decoderSurface, null, 0)

            // ---- Muxer ---------------------------------------------------------------------
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(rotation)
            var muxVideoTrack = -1
            var muxAudioTrack = -1

            decoder.start()
            encoder.start()

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            val texMatrix = FloatArray(16)
            var inputDone = false
            var decoderDone = false
            var encoderDone = false

            while (!encoderDone) {
                if (isCancelled()) throw CancellationException("Cancelled")

                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(0)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                if (!decoderDone) {
                    val outIdx = decoder.dequeueOutputBuffer(decInfo, 10_000)
                    if (outIdx >= 0) {
                        val keep = decInfo.size > 0 && shouldKeep(decInfo.presentationTimeUs)
                        decoder.releaseOutputBuffer(outIdx, keep)
                        if (keep) {
                            check(frameAvailable.tryAcquire(2, TimeUnit.SECONDS)) { "Decoder frame timeout" }
                            surfaceTexture.updateTexImage()
                            surfaceTexture.getTransformMatrix(texMatrix)
                            renderer.draw(
                                texMatrix, outW, outH,
                                options.lutStrength, options.expand, space.shaderMode
                            )
                            egl.setPresentationTimeNanos(decInfo.presentationTimeUs * 1000L)
                            egl.swapBuffers()
                            if (durationUs > 0) {
                                onProgress((decInfo.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                            }
                        }
                        if (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            decoderDone = true
                            encoder.signalEndOfInputStream()
                        }
                    }
                }

                val encTimeout = if (decoderDone) 10_000L else 0L
                while (true) {
                    val idx = encoder.dequeueOutputBuffer(encInfo, encTimeout)
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        check(!muxerStarted) { "Format changed twice" }
                        muxVideoTrack = muxer.addTrack(encoder.outputFormat)
                        if (audioTrack >= 0) {
                            muxAudioTrack = runCatching {
                                muxer.addTrack(extractor.getTrackFormat(audioTrack))
                            }.getOrDefault(-1)
                        }
                        muxer.start()
                        muxerStarted = true
                        continue
                    }
                    if (idx >= 0) {
                        val data = encoder.getOutputBuffer(idx)!!
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encInfo.size = 0
                        if (encInfo.size > 0 && muxerStarted) {
                            data.position(encInfo.offset)
                            data.limit(encInfo.offset + encInfo.size)
                            muxer.writeSampleData(muxVideoTrack, data, encInfo)
                        }
                        encoder.releaseOutputBuffer(idx, false)
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encoderDone = true
                            break
                        }
                    }
                }
            }

            if (muxerStarted && muxAudioTrack >= 0 && audioTrack >= 0) {
                copyAudio(input, audioTrack, muxer, muxAudioTrack)
            }
            onProgress(1f)
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { surfaceTexture?.release() }
            runCatching { decoderSurface?.release() }
            runCatching { renderer?.release() }
            runCatching { egl?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
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
