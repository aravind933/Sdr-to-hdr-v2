package com.example.sdr2hdr

import java.io.InputStream

/** Parsed 3D LUT from a .cube file. data is RGB floats, red index varies fastest. */
class CubeLut(val size: Int, val data: FloatArray)

object CubeParser {
    fun parse(input: InputStream): CubeLut {
        var size = 0
        val values = ArrayList<Float>()
        input.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                when {
                    line.startsWith("LUT_3D_SIZE") ->
                        size = line.split(Regex("\\s+"))[1].toInt()
                    line.startsWith("TITLE") ||
                    line.startsWith("DOMAIN_") ||
                    line.startsWith("LUT_1D_SIZE") -> Unit // ignore (1D LUTs unsupported)
                    else -> {
                        val p = line.split(Regex("\\s+"))
                        if (p.size >= 3) {
                            values.add(p[0].toFloat())
                            values.add(p[1].toFloat())
                            values.add(p[2].toFloat())
                        }
                    }
                }
            }
        }
        require(size > 0) { "Only 3D .cube LUTs are supported" }
        require(values.size == size * size * size * 3) { "Malformed LUT data" }
        return CubeLut(size, values.toFloatArray())
    }
}

enum class HdrFormat { HLG, HDR10_PQ }

/**
 * Builds an FFmpeg argument list: optional LUT (in SDR space) -> BT.2020 HDR, 10-bit HEVC.
 * Run with a maintained FFmpeg-for-Android build (FFmpegKit itself is retired).
 */
object FfmpegCommandBuilder {
    fun build(
        input: String,
        output: String,
        lutPath: String? = null,
        format: HdrFormat = HdrFormat.HLG,
        crf: Int = 18
    ): List<String> {
        val transfer = if (format == HdrFormat.HLG) "arib-std-b67" else "smpte2084"
        val npl = if (format == HdrFormat.HLG) 100 else 203

        val filters = buildList {
            if (lutPath != null) {
                // Escape characters that are special inside filter arguments
                val safe = lutPath.replace("\\", "\\\\").replace(":", "\\:").replace("'", "\\'")
                add("lut3d=file='$safe':interp=tetrahedral")
            }
            add("zscale=tin=bt709:t=linear:npl=$npl")
            add("format=gbrpf32le")
            add("zscale=p=bt2020")
            add("zscale=t=$transfer:m=bt2020nc:r=tv")
            add("format=yuv420p10le")
        }.joinToString(",")

        val x265 = "colorprim=bt2020:transfer=$transfer:colormatrix=bt2020nc" +
            if (format == HdrFormat.HDR10_PQ)
                ":hdr10=1:repeat-headers=1:master-display=" +
                    "G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,1):max-cll=1000,400"
            else ""

        return listOf(
            "-y", "-i", input,
            "-vf", filters,
            "-c:v", "libx265", "-crf", crf.toString(), "-preset", "medium",
            "-tag:v", "hvc1",
            "-x265-params", x265,
            "-c:a", "copy",
            output
        )
    }
}
