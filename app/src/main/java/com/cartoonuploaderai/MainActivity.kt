package com.cartoonuploaderai

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONTokener
import java.util.concurrent.Executors

@UnstableApi
class MainActivity : AppCompatActivity() {

    companion object {
        private const val SPACE_BASE = "https://alexcheng0072-wan27-free-video-generator.hf.space"
        private const val CALL_PATH = "/gradio_api/call/generate_video"
    }

    private lateinit var promptInput: EditText
    private lateinit var styleSpinner: Spinner
    private lateinit var aspectSpinner: Spinner
    private lateinit var durationSpinner: Spinner
    private lateinit var audioButton: Button
    private lateinit var audioLabel: TextView
    private lateinit var generateButton: Button
    private lateinit var exportButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusLabel: TextView
    private lateinit var videoPreview: VideoView

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var selectedAudioUri: Uri? = null
    private var generatedVideoFile: File? = null
    private var transformer: Transformer? = null

    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedAudioUri = uri
            try {
                contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // The temporary grant remains valid for this app session.
            }
            audioLabel.text = "Audio: ${displayName(uri)}"
            exportButton.text = "Export with selected audio"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        configureSpinners()

        audioButton.setOnClickListener { audioPicker.launch(arrayOf("audio/*")) }
        generateButton.setOnClickListener { generateVideo() }
        exportButton.setOnClickListener { exportVideo() }

        videoPreview.setOnPreparedListener { player ->
            player.isLooping = true
            videoPreview.start()
        }
    }

    private fun bindViews() {
        promptInput = findViewById(R.id.promptInput)
        styleSpinner = findViewById(R.id.styleSpinner)
        aspectSpinner = findViewById(R.id.aspectSpinner)
        durationSpinner = findViewById(R.id.durationSpinner)
        audioButton = findViewById(R.id.audioButton)
        audioLabel = findViewById(R.id.audioLabel)
        generateButton = findViewById(R.id.generateButton)
        exportButton = findViewById(R.id.exportButton)
        progressBar = findViewById(R.id.progressBar)
        statusLabel = findViewById(R.id.statusLabel)
        videoPreview = findViewById(R.id.videoPreview)
    }

    private fun configureSpinners() {
        styleSpinner.adapter = simpleAdapter(
            listOf("3D Kids Cartoon", "Bright 2D Cartoon", "Soft Clay Animation", "Storybook Animation")
        )
        aspectSpinner.adapter = simpleAdapter(listOf("Portrait 9:16", "Landscape 16:9", "Square 1:1"))
        durationSpinner.adapter = simpleAdapter(listOf("2 seconds", "3 seconds", "4 seconds", "5 seconds"))
        durationSpinner.setSelection(1)
    }

    private fun simpleAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)

    private fun generateVideo() {
        val userPrompt = promptInput.text.toString().trim()
        if (userPrompt.isBlank()) {
            promptInput.error = "Describe the video you want to create"
            return
        }

        setBusy(true, "Submitting to free AI generator…", 8)
        generatedVideoFile = null
        exportButton.isEnabled = false
        videoPreview.stopPlayback()

        val prompt = buildSafePrompt(userPrompt, styleSpinner.selectedItemPosition)
        val aspect = when (aspectSpinner.selectedItemPosition) {
            1 -> "832x480"
            2 -> "640x640"
            else -> "480x832"
        }
        val duration = durationSpinner.selectedItemPosition + 2

        ioExecutor.execute {
            try {
                updateStatus("Waiting for a free GPU slot…", 22)
                val eventId = startGradioJob(prompt, aspect, duration)
                updateStatus("AI is creating the cartoon frames…", 45)
                val remoteVideoUrl = waitForGradioResult(eventId)
                updateStatus("Downloading generated clip…", 78)
                val output = File(cacheDir, "ai_generated_${System.currentTimeMillis()}.mp4")
                downloadFile(remoteVideoUrl, output)
                generatedVideoFile = output

                runOnUiThread {
                    progressBar.progress = 100
                    statusLabel.text = "AI clip ready. Preview it, then export."
                    videoPreview.setVideoURI(Uri.fromFile(output))
                    exportButton.isEnabled = true
                    setControlsEnabled(true)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setControlsEnabled(true)
                    progressBar.progress = 0
                    statusLabel.text = friendlyError(e)
                    Toast.makeText(this, friendlyError(e), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun buildSafePrompt(userPrompt: String, styleIndex: Int): String {
        val style = when (styleIndex) {
            1 -> "bright clean 2D children's cartoon, bold friendly shapes, smooth animation"
            2 -> "soft colorful clay animation, handcrafted toy-like characters, gentle lighting"
            3 -> "whimsical illustrated storybook animation, rich colors, magical child-friendly motion"
            else -> "high-quality 3D children's cartoon animation, rounded friendly characters, vibrant colors, soft cinematic lighting"
        }
        return "$userPrompt. Style: $style. Family friendly, child safe, cheerful, expressive movement, polished composition, no text, no watermark, no scary imagery."
    }

    private fun startGradioJob(prompt: String, aspect: String, duration: Int): String {
        val body = JSONObject().put(
            "data",
            JSONArray().put(JSONObject.NULL).put(prompt).put(aspect).put(duration)
        ).toString()

        val connection = (URL(SPACE_BASE + CALL_PATH).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val responseCode = connection.responseCode
        val text = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
            .bufferedReader().use { it.readText() }
        connection.disconnect()
        if (responseCode !in 200..299) error("AI service returned HTTP $responseCode: $text")
        return JSONObject(text).optString("event_id").takeIf { it.isNotBlank() }
            ?: error("AI service did not return a job id")
    }

    private fun waitForGradioResult(eventId: String): String {
        val connection = (URL("$SPACE_BASE$CALL_PATH/$eventId").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 600_000
            setRequestProperty("Accept", "text/event-stream")
        }
        if (connection.responseCode !in 200..299) {
            val message = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            error("AI queue connection failed: $message")
        }

        var event = ""
        BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.startsWith("event:")) {
                    event = line.substringAfter(':').trim()
                } else if (line.startsWith("data:")) {
                    val data = line.substringAfter(':').trim()
                    if (event == "error") error("AI generation failed: $data")
                    if (event == "complete") {
                        val parsed = JSONTokener(data).nextValue()
                        val url = extractMp4Url(parsed)
                            ?: error("AI completed but no MP4 URL was returned")
                        connection.disconnect()
                        return url
                    }
                }
            }
        }
        connection.disconnect()
        error("AI generation ended before a video was returned")
    }

    private fun extractMp4Url(value: Any?): String? {
        return when (value) {
            is JSONObject -> {
                val preferred = listOf("url", "video", "path")
                for (key in preferred) {
                    if (value.has(key)) {
                        val found = extractMp4Url(value.opt(key))
                        if (found != null) return found
                    }
                }
                val keys = value.keys()
                while (keys.hasNext()) {
                    val found = extractMp4Url(value.opt(keys.next()))
                    if (found != null) return found
                }
                null
            }
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    val found = extractMp4Url(value.opt(i))
                    if (found != null) return found
                }
                null
            }
            is String -> normalizeVideoUrl(value)
            else -> null
        }
    }

    private fun normalizeVideoUrl(raw: String): String? {
        val text = raw.trim()
        if (text.startsWith("http://") || text.startsWith("https://")) {
            return if (text.contains(".mp4", ignoreCase = true) || text.contains("gradio_api/file")) text else null
        }
        if (text.endsWith(".mp4", ignoreCase = true)) {
            val encoded = java.net.URLEncoder.encode(text, Charsets.UTF_8.name()).replace("+", "%20")
            return "$SPACE_BASE/gradio_api/file=$encoded"
        }
        return null
    }

    private fun downloadFile(sourceUrl: String, output: File) {
        val connection = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 180_000
            instanceFollowRedirects = true
        }
        if (connection.responseCode !in 200..299) {
            val code = connection.responseCode
            connection.disconnect()
            error("Generated video download failed with HTTP $code")
        }
        connection.inputStream.use { input ->
            FileOutputStream(output).use { destination -> input.copyTo(destination) }
        }
        connection.disconnect()
        if (!output.exists() || output.length() < 8_000) error("Downloaded video file was incomplete")
    }

    private fun exportVideo() {
        val video = generatedVideoFile
        if (video == null || !video.exists()) {
            Toast.makeText(this, "Generate a video first", Toast.LENGTH_SHORT).show()
            return
        }
        val audio = selectedAudioUri
        if (audio == null) {
            setBusy(true, "Saving video to Downloads…", 88)
            ioExecutor.execute {
                try {
                    val saved = saveToDownloads(video)
                    runOnUiThread { exportFinished(saved) }
                } catch (e: Exception) {
                    runOnUiThread { exportFailed(e) }
                }
            }
            return
        }
        mixAudioAndExport(video, audio)
    }

    private fun mixAudioAndExport(video: File, audio: Uri) {
        setBusy(true, "Mixing audio under the AI video…", 82)
        val mixedFile = File(cacheDir, "kids_video_mixed_${System.currentTimeMillis()}.mp4")

        val videoItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(video)))
            .setRemoveAudio(true)
            .build()
        val audioItem = EditedMediaItem.Builder(MediaItem.fromUri(audio)).build()
        val videoSequence = EditedMediaItemSequence.withVideoFrom(listOf(videoItem))
        val audioSequence = EditedMediaItemSequence.withAudioFrom(listOf(audioItem))
            .buildUpon()
            .setIsLooping(true)
            .build()
        val composition = Composition.Builder(videoSequence, audioSequence).build()

        transformer = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    updateStatus("Finalizing file in Downloads…", 94)
                    ioExecutor.execute {
                        try {
                            val saved = saveToDownloads(mixedFile)
                            mixedFile.delete()
                            runOnUiThread { exportFinished(saved) }
                        } catch (e: Exception) {
                            runOnUiThread { exportFailed(e) }
                        }
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    mixedFile.delete()
                    exportFailed(exportException)
                }
            })
            .build()

        transformer?.start(composition, mixedFile.absolutePath)
    }

    private fun saveToDownloads(source: File): Uri {
        val name = "KidsAI_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/KidsVideoCreator")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Android could not create the Downloads file")
        try {
            contentResolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Android could not open the Downloads file")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun exportFinished(uri: Uri) {
        transformer = null
        setControlsEnabled(true)
        progressBar.progress = 100
        statusLabel.text = "Saved successfully to Downloads/KidsVideoCreator"
        Toast.makeText(this, "Video saved to Downloads/KidsVideoCreator", Toast.LENGTH_LONG).show()
    }

    private fun exportFailed(e: Exception) {
        transformer = null
        setControlsEnabled(true)
        progressBar.progress = 0
        val message = "Export failed: ${e.message ?: "unknown media error"}"
        statusLabel.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun setBusy(busy: Boolean, message: String, progress: Int) {
        setControlsEnabled(!busy)
        statusLabel.text = message
        progressBar.progress = progress
    }

    private fun setControlsEnabled(enabled: Boolean) {
        promptInput.isEnabled = enabled
        styleSpinner.isEnabled = enabled
        aspectSpinner.isEnabled = enabled
        durationSpinner.isEnabled = enabled
        audioButton.isEnabled = enabled
        generateButton.isEnabled = enabled
        exportButton.isEnabled = enabled && generatedVideoFile?.exists() == true
    }

    private fun updateStatus(message: String, progress: Int) {
        runOnUiThread {
            statusLabel.text = message
            progressBar.progress = progress
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment ?: "selected audio"
    }

    private fun friendlyError(e: Exception): String {
        val raw = e.message.orEmpty()
        return when {
            raw.contains("429") || raw.contains("queue", ignoreCase = true) ->
                "The free AI queue is busy. Try Generate again shortly."
            raw.contains("safety", ignoreCase = true) || raw.contains("content", ignoreCase = true) ->
                "The public AI safety filter rejected that prompt. Try a simpler child-friendly description."
            raw.contains("timeout", ignoreCase = true) ->
                "The free AI service took too long to respond. Try again."
            else -> "Generation failed: ${raw.ifBlank { e.javaClass.simpleName }}"
        }
    }

    override fun onDestroy() {
        transformer?.cancel()
        ioExecutor.shutdownNow()
        super.onDestroy()
    }
}
