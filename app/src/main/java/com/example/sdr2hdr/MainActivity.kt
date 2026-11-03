package com.example.sdr2hdr

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.VideoView
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

class MainActivity : Activity() {

    private data class FpsChoice(val fps: Int?, val interpolate: Boolean)

    private var videoUri: Uri? = null
    private var resultUri: Uri? = null
    private var lut: CubeLut? = null
    private var lastSpace = OutputSpace.HDR10_PQ
    private var resValues: List<Int?> = listOf(null)
    private var fpsValues: List<FpsChoice> = listOf(FpsChoice(null, false))

    private lateinit var preview: VideoView
    private lateinit var info: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var convertBtn: Button
    private lateinit var cancelBtn: Button
    private lateinit var previewSourceBtn: Button
    private lateinit var previewResultBtn: Button
    private lateinit var spaceSpinner: Spinner
    private lateinit var resSpinner: Spinner
    private lateinit var fpsSpinner: Spinner
    private lateinit var strengthBar: SeekBar
    private lateinit var brightnessBar: SeekBar
    private lateinit var effectBar: SeekBar
    private lateinit var sharpBar: SeekBar
    @Volatile private var cancelled = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(t: String) = TextView(this).apply { text = t; setPadding(0, dp(12), 0, 0) }
    private fun spinner(items: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
    }

    /** Adds a titled slider whose title shows the current value. */
    private fun seekRow(root: LinearLayout, title: String, max: Int, initial: Int, fmt: (Int) -> String): SeekBar {
        val tv = TextView(this).apply { setPadding(0, dp(12), 0, 0) }
        val bar = SeekBar(this).apply { this.max = max; progress = initial }
        fun update() { tv.text = "$title: ${fmt(bar.progress)}" }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = update()
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        update()
        root.addView(tv)
        root.addView(bar)
        return bar
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(16))
        }

        // ---- Preview ----
        preview = VideoView(this)
        preview.setMediaController(MediaController(this).also { it.setAnchorView(preview) })
        preview.setOnPreparedListener { preview.seekTo(1) }
        root.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220)))

        val previewRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        previewSourceBtn = Button(this).apply { text = "Preview source"; isEnabled = false }
        previewResultBtn = Button(this).apply { text = "Preview result"; isEnabled = false }
        previewRow.addView(previewSourceBtn, LinearLayout.LayoutParams(0, -2, 1f))
        previewRow.addView(previewResultBtn, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(previewRow)

        // ---- Inputs ----
        val pickVideo = Button(this).apply { text = "1. Pick SDR video" }
        val pickLut = Button(this).apply { text = "2. Pick .cube LUT (optional)" }
        info = TextView(this).apply { text = "No video selected." }
        root.addView(pickVideo)
        root.addView(pickLut)
        root.addView(info)

        // ---- Output options ----
        spaceSpinner = spinner(OutputSpace.values().map { it.label })   // default = HDR10 PQ
        resSpinner = spinner(listOf("Source"))
        fpsSpinner = spinner(listOf("Source"))
        root.addView(label("Colour space"))
        root.addView(spaceSpinner)
        root.addView(label("Resolution"))
        root.addView(resSpinner)
        root.addView(label("Frame rate"))
        root.addView(fpsSpinner)

        strengthBar = seekRow(root, "LUT strength", 100, 100) { "$it%" }
        brightnessBar = seekRow(root, "Brightness (SDR white)", 100, 30) { "${100 + it * 10} nits" }
        effectBar = seekRow(root, "HDR effect (highlight boost)", 100, 30) { "$it%" }
        sharpBar = seekRow(root, "Sharpness", 100, 0) { "$it%" }

        // ---- Run ----
        convertBtn = Button(this).apply { text = "3. Convert"; isEnabled = false }
        cancelBtn = Button(this).apply { text = "Cancel"; visibility = View.GONE }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        status = TextView(this).apply { text = "Pick a video to begin."; setPadding(0, dp(12), 0, 0) }
        root.addView(convertBtn)
        root.addView(cancelBtn)
        root.addView(progress)
        root.addView(status)

        setContentView(ScrollView(this).apply { addView(root) })

        pickVideo.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "video/*" }, REQ_VIDEO)
        }
        pickLut.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }, REQ_LUT)
        }
        previewSourceBtn.setOnClickListener { videoUri?.let { playInPreview(it, hdr = false) } }
        previewResultBtn.setOnClickListener {
            resultUri?.let { playInPreview(it, hdr = lastSpace != OutputSpace.SDR_709) }
        }
        convertBtn.setOnClickListener { startConversion() }
        cancelBtn.setOnClickListener { cancelled = true; status.text = "Cancelling..." }
    }

    private fun playInPreview(uri: Uri, hdr: Boolean) {
        window.colorMode = if (hdr) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
        preview.setVideoURI(uri)
        preview.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_VIDEO -> onVideoPicked(uri)
            REQ_LUT -> try {
                lut = contentResolver.openInputStream(uri)!!.use { CubeParser.parse(it) }
                status.text = "LUT loaded (${lut!!.size}^3)."
            } catch (e: Exception) {
                lut = null
                status.text = "LUT error: ${e.message}"
            }
        }
    }

    private fun onVideoPicked(uri: Uri) {
        val src = try {
            SdrToHdrTranscoder.probe(this, uri)
        } catch (e: Exception) {
            status.text = "Could not read video: ${e.message}"
            return
        }
        videoUri = uri
        previewSourceBtn.isEnabled = true
        playInPreview(uri, hdr = false)

        val shortSide = minOf(src.width, src.height)
        info.text = "Source: ${src.width}x${src.height}, ${src.fps} fps" +
            (if (src.rotation != 0) ", rotated ${src.rotation}°" else "") +
            if (src.isHdr) " (already HDR)" else " (SDR)"

        // Resolution: source, plus standard sizes above (upscale) and below (downscale)
        val resLabels = mutableListOf("Source (${src.width}x${src.height})")
        val resVals = mutableListOf<Int?>(null)
        for (s in listOf(2160, 1440, 1080, 720, 480)) {
            if (s == shortSide) continue
            resLabels.add(if (s > shortSide) "${s}p (upscale)" else "${s}p")
            resVals.add(s)
        }
        resValues = resVals
        resSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resLabels)

        // Frame rate: source, lower rates (drop frames), or 2x via optical flow
        val fpsLabels = mutableListOf("Source (${src.fps} fps)")
        val fpsVals = mutableListOf(FpsChoice(null, false))
        for (f in listOf(60, 50, 30, 25, 24)) {
            if (f < src.fps) { fpsLabels.add("$f fps (drop frames)"); fpsVals.add(FpsChoice(f, false)) }
        }
        if (src.fps <= 30) {
            fpsLabels.add("${src.fps * 2} fps (optical flow, slow)")
            fpsVals.add(FpsChoice(null, true))
        }
        fpsValues = fpsVals
        fpsSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fpsLabels)

        if (src.isHdr) {
            convertBtn.isEnabled = false
            status.text = "This video is already HDR. Pick an SDR video."
        } else {
            convertBtn.isEnabled = true
            status.text = "Ready."
        }
    }

    private fun startConversion() {
        val input = videoUri ?: return
        val space = OutputSpace.values()[spaceSpinner.selectedItemPosition]
        val fps = fpsValues.getOrElse(fpsSpinner.selectedItemPosition) { FpsChoice(null, false) }
        val options = SdrToHdrTranscoder.Options(
            lut = lut,
            lutStrength = strengthBar.progress / 100f,
            space = space,
            whiteNits = 100f + brightnessBar.progress * 10f,
            hdrEffect = effectBar.progress / 100f,
            sharpen = sharpBar.progress / 100f * 1.5f,
            targetShortSide = resValues.getOrNull(resSpinner.selectedItemPosition),
            targetFps = fps.fps,
            interpolate2x = fps.interpolate
        )

        convertBtn.isEnabled = false
        cancelBtn.visibility = View.VISIBLE
        previewResultBtn.isEnabled = false
        cancelled = false
        progress.progress = 0
        status.text = "Converting to ${space.label}..."
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val tmp = File(cacheDir, "out_${System.currentTimeMillis()}.mp4")
        Thread {
            val result = runCatching {
                SdrToHdrTranscoder(this).transcode(
                    input, tmp.absolutePath, options,
                    onProgress = { p -> runOnUiThread { progress.progress = (p * 100).toInt() } },
                    isCancelled = { cancelled }
                )
                saveToMovies(tmp)
            }
            tmp.delete()
            runOnUiThread {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                convertBtn.isEnabled = true
                cancelBtn.visibility = View.GONE
                result.fold(
                    onSuccess = {
                        resultUri = it
                        lastSpace = space
                        previewResultBtn.isEnabled = true
                        status.text = "Done. Saved to Movies/SDR2HDR."
                        playInPreview(it, hdr = space != OutputSpace.SDR_709)
                    },
                    onFailure = {
                        status.text = if (it is CancellationException) "Cancelled." else "Failed: ${it.message}"
                    }
                )
            }
        }.start()
    }

    private fun saveToMovies(file: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "HDR_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SDR2HDR")
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create output file")
        contentResolver.openOutputStream(uri)!!.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        return uri
    }

    override fun onDestroy() {
        cancelled = true
        super.onDestroy()
    }

    private companion object {
        const val REQ_VIDEO = 1
        const val REQ_LUT = 2
    }
}
