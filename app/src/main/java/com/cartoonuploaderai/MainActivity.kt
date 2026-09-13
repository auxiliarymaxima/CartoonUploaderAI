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
    private lateinit var modeSpinner: Spinner
    private lateinit var modeInfo: TextView
    private lateinit var styleSpinner: Spinner
    private lateinit var aspectSpinner: Spinner
    private lateinit var durationSpinner: Spinner
    private lateinit var audioButton: Button
    private lateinit var audioLabel: TextView
    private lateinit var generateButton: Button
    private lateinit var exportButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusLabel: TextView
    private lateinit var diagnosticLabel: TextView
    private lateinit var testServiceButton: Button
    private lateinit var copyDiagnosticsButton: Button
    private lateinit var clearGenerationButton: Button
    private lateinit var videoPreview: VideoView

    private lateinit var diagnostics: DiagnosticCenter

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var selectedAudioUri: Uri? = null
    private var generatedVideoFile: File? = null
    private var transformer: Transformer? = null

    // Story Mode cache. Completed scenes survive a retry during this app session.
    private val storySceneFiles = mutableListOf<File>()
    private var storyCacheKey: String? = null

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

        diagnostics =
            DiagnosticCenter(
                this,
                SPACE_BASE + CALL_PATH
            )

        bindViews()
        configureSpinners()

        diagnosticLabel.text =
            diagnostics.restoredSummary()

        audioButton.setOnClickListener { audioPicker.launch(arrayOf("audio/*")) }
        generateButton.setOnClickListener {
            if (modeSpinner.selectedItemPosition == 0) {
                generateVideo()
            } else {
                generateStoryVideo()
            }
        }
        exportButton.setOnClickListener {
            exportVideo()
        }

        testServiceButton.setOnClickListener {
            testAiService()
        }

        copyDiagnosticsButton.setOnClickListener {
            copyDiagnostics()
        }

        clearGenerationButton.setOnClickListener {
            clearFailedGeneration()
        }

        videoPreview.setOnPreparedListener { player ->
            player.isLooping = true
            videoPreview.start()
        }
    }

    private fun bindViews() {
        promptInput = findViewById(R.id.promptInput)
        modeSpinner = findViewById(R.id.modeSpinner)
        modeInfo = findViewById(R.id.modeInfo)
        styleSpinner = findViewById(R.id.styleSpinner)
        aspectSpinner = findViewById(R.id.aspectSpinner)
        durationSpinner = findViewById(R.id.durationSpinner)
        audioButton = findViewById(R.id.audioButton)
        audioLabel = findViewById(R.id.audioLabel)
        generateButton = findViewById(R.id.generateButton)
        exportButton = findViewById(R.id.exportButton)
        progressBar = findViewById(R.id.progressBar)
        statusLabel =
            findViewById(R.id.statusLabel)

        diagnosticLabel =
            findViewById(R.id.diagnosticLabel)

        testServiceButton =
            findViewById(R.id.testServiceButton)

        copyDiagnosticsButton =
            findViewById(R.id.copyDiagnosticsButton)

        clearGenerationButton =
            findViewById(R.id.clearGenerationButton)

        videoPreview =
            findViewById(R.id.videoPreview)
    }

    private fun configureSpinners() {
        modeSpinner.adapter = simpleAdapter(
            listOf(
                "Single clip (2-5 sec)",
                "Story 10 sec (2 scenes)",
                "Story 15 sec (3 scenes)",
                "Story 20 sec (4 scenes)",
                "Story 25 sec (5 scenes)",
                "Story 30 sec (6 scenes)"
            )
        )

        styleSpinner.adapter = simpleAdapter(
            listOf("3D Kids Cartoon", "Bright 2D Cartoon", "Soft Clay Animation", "Storybook Animation")
        )
        aspectSpinner.adapter = simpleAdapter(listOf("Portrait 9:16", "Landscape 16:9", "Square 1:1"))
        durationSpinner.adapter = simpleAdapter(listOf("2 seconds", "3 seconds", "4 seconds", "5 seconds"))
        // Maximize the current provider by default while preserving 2-4 second choices.
        durationSpinner.setSelection(3)
    }

    private fun simpleAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)

    private fun generateVideo() {
        val userPrompt = promptInput.text.toString().trim()
        if (userPrompt.isBlank()) {
            promptInput.error = "Describe the video you want to create"
            return
        }

        diagnostics.begin("SINGLE_CLIP")
        diagnostics.setScene(1, 1)
        updateDiagnosticSummary()

        setBusy(
            true,
            "Submitting to free AI generator…",
            8
        )
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
                val snapshot =
                    diagnostics.capture(
                        e,
                        "SINGLE_GENERATION"
                    )

                runOnUiThread {
                    setControlsEnabled(true)
                    progressBar.progress = 0

                    showDiagnosticFailure(
                        snapshot,
                        "Generation failed"
                    )
                }
            }
        }
    }


    private fun generateStoryVideo() {
        val userPrompt = promptInput.text.toString().trim()
        if (userPrompt.isBlank()) {
            promptInput.error = "Describe the story you want to create"
            return
        }

        // Position 1 = 2 scenes, position 5 = 6 scenes.
        val sceneCount = modeSpinner.selectedItemPosition + 1
        val styleIndex = styleSpinner.selectedItemPosition
        val aspect = when (aspectSpinner.selectedItemPosition) {
            1 -> "832x480"
            2 -> "640x640"
            else -> "480x832"
        }

        val cacheKey = listOf(
            userPrompt,
            sceneCount.toString(),
            styleIndex.toString(),
            aspect
        ).joinToString("|")

        storySceneFiles.removeAll { !it.exists() }

        if (storyCacheKey != cacheKey) {
            storySceneFiles.forEach { it.delete() }
            storySceneFiles.clear()
            storyCacheKey = cacheKey
        }

        diagnostics.begin(
            "STORY_${sceneCount}_SCENES"
        )

        diagnostics.stage(
            "STORY_PLAN",
            "requestedScenes=$sceneCount cachedScenes=${
                storySceneFiles.count { it.exists() }
            }"
        )

        updateDiagnosticSummary()

        val plannedScenes =
            planStoryScenes(
                userPrompt,
                sceneCount
            )

        generatedVideoFile = null
        exportButton.isEnabled = false
        videoPreview.stopPlayback()

        modeInfo.text =
            "Story Mode: $sceneCount sequential 5-second scenes. Completed scenes are kept if a later scene fails."

        val completed = storySceneFiles.size.coerceAtMost(sceneCount)
        setBusy(
            true,
            if (completed > 0) {
                "Resuming story after $completed completed scene(s)..."
            } else {
                "Starting $sceneCount-scene story..."
            },
            5
        )

        ioExecutor.execute {
            try {
                for (index in completed until sceneCount) {
                    val sceneNumber =
                        index + 1

                    diagnostics.setScene(
                        sceneNumber,
                        sceneCount
                    )

                    updateDiagnosticSummary()

                    val baseProgress =
                        8 + ((index * 68) / sceneCount)

                    updateStatus(
                        "Scene $sceneNumber/$sceneCount: waiting for free GPU...",
                        baseProgress
                    )

                    val prompt = buildStoryScenePrompt(
                        plannedScenes[index],
                        index,
                        sceneCount,
                        styleIndex
                    )

                    val eventId = startGradioJob(prompt, aspect, 5)

                    updateStatus(
                        "Scene $sceneNumber/$sceneCount: AI is generating...",
                        baseProgress + 6
                    )

                    val remoteVideoUrl = waitForGradioResult(eventId)

                    updateStatus(
                        "Scene $sceneNumber/$sceneCount: downloading...",
                        baseProgress + 10
                    )

                    val output = File(
                        cacheDir,
                        "story_${cacheKey.hashCode()}_${index}_${System.currentTimeMillis()}.mp4"
                    )

                    downloadFile(remoteVideoUrl, output)
                    storySceneFiles.add(output)

                    updateStatus(
                        "Scene $sceneNumber/$sceneCount complete.",
                        baseProgress + 12
                    )
                }

                runOnUiThread {
                    stitchStoryScenes(storySceneFiles.take(sceneCount))
                }

            } catch (e: Exception) {

                val snapshot =
                    diagnostics.capture(
                        e,
                        "STORY_GENERATION"
                    )

                runOnUiThread {

                    setControlsEnabled(true)

                    progressBar.progress = 0

                    val completedNow =
                        storySceneFiles.count {
                            it.exists()
                        }

                    showDiagnosticFailure(
                        snapshot,
                        "Story paused after $completedNow/$sceneCount scene(s)"
                    )

                    modeInfo.text =
                        "Completed scenes were kept. " +
                        "Tap Generate again to retry " +
                        "from the failed scene."
                }
            }
        }
    }

    private fun planStoryScenes(rawPrompt: String, sceneCount: Int): List<String> {
        val normalized = rawPrompt
            .replace("\n", ". ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val firstPass = normalized
            .split(
                Regex(
                    "(?i)(?:[.!?;]+|\\band then\\b|\\bthen\\b|\\bnext\\b)"
                )
            )
            .map { it.trim(' ', ',', '-') }
            .filter { it.isNotBlank() }

        val expanded = firstPass.flatMap { clause ->
            if (clause.length > 170 && clause.contains(",")) {
                clause.split(",")
                    .map { it.trim() }
                    .filter { it.length > 12 }
            } else {
                listOf(clause)
            }
        }

        val source = if (expanded.isNotEmpty()) expanded else listOf(normalized)

        return (0 until sceneCount).map { index ->
            when {
                index < source.size -> source[index]

                else -> {
                    val base = source[(index - 1).coerceAtLeast(0) % source.size]
                    "Continue the same story naturally from the previous scene. " +
                    "Focus on $base with one new simple action."
                }
            }
        }
    }

    private fun buildStoryScenePrompt(
        scene: String,
        index: Int,
        total: Int,
        styleIndex: Int
    ): String {
        val continuity = if (index == 0) {
            "Establish the main characters clearly. Keep their clothing, face, hair, colors and proportions easy to recognize."
        } else {
            "Continue directly from the previous scene. Keep exactly the same main characters, clothing, face, hair, colors and proportions."
        }

        val camera = when (index % 4) {
            0 -> "Use a clear medium-wide cinematic shot with smooth gentle movement."
            1 -> "Use a smooth tracking shot and keep the characters clearly visible."
            2 -> "Use a medium shot with gentle camera motion and a readable background."
            else -> "Use a wider cinematic shot with smooth child-friendly movement."
        }

        val focused =
            "Scene ${index + 1} of $total. $scene. $continuity $camera " +
            "Show one clear main action in this clip. Avoid introducing unnecessary new characters."

        return buildSafePrompt(focused, styleIndex)
    }

    private fun stitchStoryScenes(scenes: List<File>) {
        if (scenes.isEmpty()) {
            setControlsEnabled(true)
            statusLabel.text = "No completed scenes are available to join."
            return
        }

        diagnostics.stage(
            "STITCH_START",
            "sceneCount=${scenes.size}"
        )

        updateDiagnosticSummary()

        setBusy(
            true,
            "Joining ${scenes.size} scenes locally...",
            88
        )

        val output = File(
            cacheDir,
            "story_joined_${System.currentTimeMillis()}.mp4"
        )

        val videoItems = scenes.map { scene ->
            EditedMediaItem.Builder(
                MediaItem.fromUri(Uri.fromFile(scene))
            )
                .setRemoveAudio(true)
                .build()
        }

        val sequence = EditedMediaItemSequence.withVideoFrom(videoItems)
        val composition = Composition.Builder(sequence).build()

        transformer = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .addListener(object : Transformer.Listener {

                override fun onCompleted(
                    composition: Composition,
                    exportResult: ExportResult
                ) {
                    transformer = null
                    generatedVideoFile = output

                    diagnostics.stage(
                        "STITCH_OK",
                        "sceneCount=${scenes.size} " +
                        "outputBytes=${output.length()}"
                    )

                    runOnUiThread {

                        updateDiagnosticSummary()
                        progressBar.progress = 100
                        statusLabel.text =
                            "Story ready: ${scenes.size * 5} seconds. Preview it, then export."
                        modeInfo.text =
                            "Story Mode completed ${scenes.size}/${scenes.size} scenes successfully."
                        videoPreview.setVideoURI(Uri.fromFile(output))
                        exportButton.isEnabled = true
                        setControlsEnabled(true)
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    transformer = null
                    output.delete()

                    val snapshot =
                        diagnostics.capture(
                            diagnostics.fail(
                                DiagnosticCategory.STITCH_ERROR,
                                "STITCH",
                                exportException.message
                                    ?: "Unknown Media3 stitching error",
                                exportException
                            ),
                            "STITCH"
                        )

                    runOnUiThread {

                        setControlsEnabled(true)

                        progressBar.progress = 0

                        showDiagnosticFailure(
                            snapshot,
                            "Story joining failed"
                        )
                    }
                }
            })
            .build()

        transformer?.start(composition, output.absolutePath)
    }

    private fun buildSafePrompt(userPrompt: String, styleIndex: Int): String {
        val style = when (styleIndex) {
            1 -> "bright clean 2D children's cartoon, bold friendly shapes, smooth animation"
            2 -> "soft colorful clay animation, handcrafted toy-like characters, gentle lighting"
            3 -> "whimsical illustrated storybook animation, rich colors, magical child-friendly motion"
            else -> "high-quality 3D children's cartoon animation, rounded friendly characters, vibrant colors, soft cinematic lighting"
        }
        val finalPrompt =
            "$userPrompt. Style: $style. Family friendly, child safe, cheerful, " +
            "expressive movement, polished composition, no text, no watermark, no scary imagery."

        // The current public Wan endpoint accepts at most 600 characters.
        return finalPrompt.take(590)
    }

    private fun startGradioJob(
        prompt: String,
        aspect: String,
        duration: Int
    ): String {

        val body =
            JSONObject()
                .put(
                    "data",
                    JSONArray()
                        .put(JSONObject.NULL)
                        .put(prompt)
                        .put(aspect)
                        .put(duration)
                )
                .toString()

        diagnostics.stage(
            "SUBMIT_REQUEST",
            "POST $CALL_PATH " +
            "promptChars=${prompt.length} " +
            "aspect=$aspect duration=${duration}s"
        )

        updateDiagnosticSummary()

        try {

            val connection =
                (
                    URL(
                        SPACE_BASE + CALL_PATH
                    ).openConnection()
                    as HttpURLConnection
                ).apply {

                    requestMethod = "POST"
                    connectTimeout = 30_000
                    readTimeout = 30_000
                    doOutput = true

                    setRequestProperty(
                        "Content-Type",
                        "application/json"
                    )

                    setRequestProperty(
                        "Accept",
                        "application/json"
                    )
                }

            try {

                connection.outputStream.use {
                    it.write(
                        body.toByteArray(
                            Charsets.UTF_8
                        )
                    )
                }

                val responseCode =
                    connection.responseCode

                val stream =
                    if (
                        responseCode in 200..299
                    ) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }

                val text =
                    stream
                        ?.bufferedReader()
                        ?.use {
                            it.readText()
                        }
                        .orEmpty()

                diagnostics.stage(
                    "SUBMIT_RESPONSE",
                    "HTTP $responseCode body=${
                        diagSnippet(
                            text,
                            350
                        )
                    }"
                )

                if (
                    responseCode !in 200..299
                ) {

                    val category =
                        when {

                            responseCode == 429 ->
                                DiagnosticCategory.QUEUE_ERROR

                            responseCode in 500..599 ->
                                DiagnosticCategory.SERVICE_ERROR

                            else ->
                                DiagnosticCategory.API_VALIDATION_ERROR
                        }

                    throw diagnostics.fail(
                        category,
                        "SUBMIT_HTTP",
                        "HTTP $responseCode body=${
                            diagSnippet(
                                text,
                                700
                            )
                        }"
                    )
                }

                val eventId =
                    try {
                        JSONObject(text)
                            .optString(
                                "event_id"
                            )
                    } catch (
                        e: Exception
                    ) {

                        throw diagnostics.fail(
                            DiagnosticCategory.RESULT_PARSE_ERROR,
                            "SUBMIT_PARSE",
                            "Could not parse submit response: ${
                                diagSnippet(
                                    text,
                                    700
                                )
                            }",
                            e
                        )
                    }

                if (
                    eventId.isBlank()
                ) {

                    throw diagnostics.fail(
                        DiagnosticCategory.RESULT_PARSE_ERROR,
                        "SUBMIT_PARSE",
                        "Successful HTTP response did not contain event_id. body=${
                            diagSnippet(
                                text,
                                700
                            )
                        }"
                    )
                }

                diagnostics.setEventId(
                    eventId
                )

                diagnostics.stage(
                    "JOB_ACCEPTED",
                    "eventId=$eventId"
                )

                updateDiagnosticSummary()

                return eventId

            } finally {

                connection.disconnect()
            }

        } catch (
            e: DiagnosticFailure
        ) {

            throw e

        } catch (
            e: Exception
        ) {

            throw diagnostics.asFailure(
                "SUBMIT_REQUEST",
                e
            )
        }
    }

    private fun waitForGradioResult(
        eventId: String
    ): String {

        diagnostics.stage(
            "QUEUE_CONNECT",
            "eventId=$eventId"
        )

        updateDiagnosticSummary()

        try {

            val connection =
                (
                    URL(
                        "$SPACE_BASE$CALL_PATH/$eventId"
                    ).openConnection()
                    as HttpURLConnection
                ).apply {

                    requestMethod = "GET"
                    connectTimeout = 30_000
                    readTimeout = 600_000

                    setRequestProperty(
                        "Accept",
                        "text/event-stream"
                    )
                }

            try {

                val responseCode =
                    connection.responseCode

                if (
                    responseCode !in 200..299
                ) {

                    val message =
                        connection
                            .errorStream
                            ?.bufferedReader()
                            ?.use {
                                it.readText()
                            }
                            .orEmpty()

                    val category =
                        when {

                            responseCode == 429 ->
                                DiagnosticCategory.QUEUE_ERROR

                            responseCode in 500..599 ->
                                DiagnosticCategory.SERVICE_ERROR

                            else ->
                                DiagnosticCategory.API_VALIDATION_ERROR
                        }

                    throw diagnostics.fail(
                        category,
                        "QUEUE_HTTP",
                        "HTTP $responseCode body=${
                            diagSnippet(
                                message,
                                700
                            )
                        }"
                    )
                }

                diagnostics.stage(
                    "QUEUE_CONNECTED",
                    "HTTP $responseCode"
                )

                updateDiagnosticSummary()

                var event = ""

                BufferedReader(
                    InputStreamReader(
                        connection.inputStream
                    )
                ).use { reader ->

                    while (true) {

                        val line =
                            reader.readLine()
                                ?: break

                        if (
                            line.startsWith(
                                "event:"
                            )
                        ) {

                            event =
                                line.substringAfter(
                                    ':'
                                ).trim()

                            diagnostics.stage(
                                "SSE_EVENT",
                                "event=$event"
                            )

                        } else if (
                            line.startsWith(
                                "data:"
                            )
                        ) {

                            val data =
                                line.substringAfter(
                                    ':'
                                ).trim()

                            diagnostics.stage(
                                "SSE_DATA",
                                "event=$event data=${
                                    diagSnippet(
                                        data,
                                        300
                                    )
                                }"
                            )

                            if (
                                event == "error"
                            ) {

                                val category =
                                    if (
                                        data.contains(
                                            "queue",
                                            ignoreCase = true
                                        ) ||
                                        data.contains(
                                            "busy",
                                            ignoreCase = true
                                        ) ||
                                        data.contains(
                                            "429"
                                        )
                                    ) {

                                        DiagnosticCategory.QUEUE_ERROR

                                    } else {

                                        DiagnosticCategory.SERVICE_ERROR
                                    }

                                throw diagnostics.fail(
                                    category,
                                    "SSE_ERROR",
                                    "event=error data=${
                                        diagSnippet(
                                            data,
                                            900
                                        )
                                    } eventId=$eventId"
                                )
                            }

                            if (
                                event ==
                                "complete"
                            ) {

                                val parsed =
                                    try {

                                        JSONTokener(
                                            data
                                        ).nextValue()

                                    } catch (
                                        e: Exception
                                    ) {

                                        throw diagnostics.fail(
                                            DiagnosticCategory.RESULT_PARSE_ERROR,
                                            "RESULT_JSON_PARSE",
                                            "complete event was not valid JSON: ${
                                                diagSnippet(
                                                    data,
                                                    900
                                                )
                                            }",
                                            e
                                        )
                                    }

                                val url =
                                    extractMp4Url(
                                        parsed
                                    )

                                if (
                                    url == null
                                ) {

                                    throw diagnostics.fail(
                                        DiagnosticCategory.RESULT_PARSE_ERROR,
                                        "RESULT_VIDEO_URL",
                                        "complete event contained no usable MP4 URL: ${
                                            diagSnippet(
                                                data,
                                                1200
                                            )
                                        }"
                                    )
                                }

                                diagnostics.stage(
                                    "RESULT_READY",
                                    "videoUrl=${
                                        diagSnippet(
                                            url,
                                            300
                                        )
                                    }"
                                )

                                updateDiagnosticSummary()

                                return url
                            }
                        }
                    }
                }

                throw diagnostics.fail(
                    DiagnosticCategory.QUEUE_ERROR,
                    "QUEUE_STREAM_ENDED",
                    "Event stream ended before complete/error event. eventId=$eventId"
                )

            } finally {

                connection.disconnect()
            }

        } catch (
            e: DiagnosticFailure
        ) {

            throw e

        } catch (
            e: Exception
        ) {

            throw diagnostics.asFailure(
                "QUEUE_STREAM",
                e
            )
        }
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

    private fun downloadFile(
        sourceUrl: String,
        output: File
    ) {

        diagnostics.stage(
            "DOWNLOAD_REQUEST",
            "GET ${
                diagSnippet(
                    sourceUrl,
                    320
                )
            }"
        )

        updateDiagnosticSummary()

        try {

            val connection =
                (
                    URL(
                        sourceUrl
                    ).openConnection()
                    as HttpURLConnection
                ).apply {

                    connectTimeout = 30_000
                    readTimeout = 180_000
                    instanceFollowRedirects = true
                }

            try {

                val responseCode =
                    connection.responseCode

                diagnostics.stage(
                    "DOWNLOAD_RESPONSE",
                    "HTTP $responseCode " +
                    "contentType=${connection.contentType} " +
                    "contentLength=${connection.contentLengthLong}"
                )

                if (
                    responseCode !in 200..299
                ) {

                    val errorText =
                        connection
                            .errorStream
                            ?.bufferedReader()
                            ?.use {
                                it.readText()
                            }
                            .orEmpty()

                    throw diagnostics.fail(
                        DiagnosticCategory.DOWNLOAD_ERROR,
                        "DOWNLOAD_HTTP",
                        "HTTP $responseCode body=${
                            diagSnippet(
                                errorText,
                                700
                            )
                        }"
                    )
                }

                connection
                    .inputStream
                    .use { input ->

                        FileOutputStream(
                            output
                        ).use { destination ->

                            input.copyTo(
                                destination
                            )
                        }
                    }

            } finally {

                connection.disconnect()
            }

            if (
                !output.exists() ||
                output.length() < 8_000
            ) {

                throw diagnostics.fail(
                    DiagnosticCategory.DOWNLOAD_ERROR,
                    "DOWNLOAD_FILE_CHECK",
                    "Downloaded file missing or too small. " +
                    "exists=${output.exists()} " +
                    "bytes=${
                        if (
                            output.exists()
                        ) {
                            output.length()
                        } else {
                            0
                        }
                    }"
                )
            }

            diagnostics.stage(
                "DOWNLOAD_OK",
                "bytes=${output.length()} " +
                "file=${output.name}"
            )

            updateDiagnosticSummary()

        } catch (
            e: DiagnosticFailure
        ) {

            throw e

        } catch (
            e: Exception
        ) {

            throw diagnostics.asFailure(
                "DOWNLOAD",
                e
            )
        }
    }

    private fun exportVideo() {
        val video = generatedVideoFile
        if (video == null || !video.exists()) {
            Toast.makeText(this, "Generate a video first", Toast.LENGTH_SHORT).show()
            return
        }
        val audio = selectedAudioUri

        diagnostics.stage(
            "EXPORT_START",
            "audioSelected=${audio != null} " +
            "videoBytes=${video.length()}"
        )

        updateDiagnosticSummary()
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
        diagnostics.stage(
            "AUDIO_MIX_START",
            "audioUri=$audio"
        )

        updateDiagnosticSummary()

        setBusy(
            true,
            "Mixing audio under the AI video…",
            82
        )
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
                override fun onCompleted(
                    composition: Composition,
                    exportResult: ExportResult
                ) {

                    diagnostics.stage(
                        "AUDIO_MIX_OK",
                        "mixedBytes=${mixedFile.length()}"
                    )

                    updateDiagnosticSummary()

                    updateStatus(
                        "Finalizing file in Downloads…",
                        94
                    )
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

                    exportFailed(
                        diagnostics.fail(
                            DiagnosticCategory.EXPORT_ERROR,
                            "AUDIO_MIX",
                            exportException.message
                                ?: "Unknown Media3 audio mix error",
                            exportException
                        )
                    )
                }
            })
            .build()

        transformer?.start(composition, mixedFile.absolutePath)
    }

    private fun saveToDownloads(
        source: File
    ): Uri {

        diagnostics.stage(
            "SAVE_TO_PHONE_START",
            "source=${source.name} " +
            "bytes=${source.length()}"
        )

        updateDiagnosticSummary()

        val name =
            "KidsAI_${System.currentTimeMillis()}.mp4"

        var uri: Uri? = null

        try {

            val values =
                ContentValues().apply {

                    put(
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        name
                    )

                    put(
                        MediaStore.MediaColumns.MIME_TYPE,
                        "video/mp4"
                    )

                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/KidsVideoCreator"
                    )

                    put(
                        MediaStore.MediaColumns.IS_PENDING,
                        1
                    )
                }

            uri =
                contentResolver.insert(
                    MediaStore
                        .Downloads
                        .EXTERNAL_CONTENT_URI,
                    values
                )
                    ?: throw diagnostics.fail(
                        DiagnosticCategory.EXPORT_ERROR,
                        "SAVE_TO_PHONE_INSERT",
                        "MediaStore returned null while creating the Downloads file."
                    )

            contentResolver
                .openOutputStream(
                    uri
                )
                ?.use { output ->

                    source
                        .inputStream()
                        .use { input ->

                            input.copyTo(
                                output
                            )
                        }
                }
                ?: throw diagnostics.fail(
                    DiagnosticCategory.EXPORT_ERROR,
                    "SAVE_TO_PHONE_STREAM",
                    "Android could not open the output stream."
                )

            values.clear()

            values.put(
                MediaStore.MediaColumns.IS_PENDING,
                0
            )

            contentResolver.update(
                uri,
                values,
                null,
                null
            )

            diagnostics.stage(
                "SAVE_TO_PHONE_OK",
                "uri=$uri filename=$name"
            )

            updateDiagnosticSummary()

            return uri

        } catch (
            e: DiagnosticFailure
        ) {

            uri?.let {
                contentResolver.delete(
                    it,
                    null,
                    null
                )
            }

            throw e

        } catch (
            e: Exception
        ) {

            uri?.let {
                contentResolver.delete(
                    it,
                    null,
                    null
                )
            }

            throw diagnostics.fail(
                DiagnosticCategory.EXPORT_ERROR,
                "SAVE_TO_PHONE",
                "${e.javaClass.simpleName}: ${
                    e.message ?: "(no message)"
                }",
                e
            )
        }
    }

    private fun exportFinished(uri: Uri) {
        transformer = null
        setControlsEnabled(true)
        progressBar.progress = 100
        diagnostics.stage(
            "EXPORT_COMPLETE",
            "savedUri=$uri"
        )

        updateDiagnosticSummary()

        statusLabel.text =
            "Saved successfully to Downloads/KidsVideoCreator"

        Toast.makeText(
            this,
            "Video saved to Downloads/KidsVideoCreator",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun exportFailed(
        e: Exception
    ) {

        transformer = null

        setControlsEnabled(true)

        progressBar.progress = 0

        val snapshot =
            diagnostics.capture(
                e,
                "EXPORT"
            )

        showDiagnosticFailure(
            snapshot,
            "Export failed"
        )
    }

    private fun testAiService() {

        diagnostics.begin(
            "SERVICE_TEST"
        )

        diagnostics.stage(
            "SERVICE_TEST_REQUEST",
            "GET $SPACE_BASE"
        )

        updateDiagnosticSummary()

        setBusy(
            true,
            "Testing external AI service…",
            15
        )

        ioExecutor.execute {

            try {

                val started =
                    System.currentTimeMillis()

                val connection =
                    (
                        URL(
                            SPACE_BASE
                        ).openConnection()
                        as HttpURLConnection
                    ).apply {

                        requestMethod = "GET"
                        connectTimeout = 15_000
                        readTimeout = 15_000
                        instanceFollowRedirects = true
                    }

                try {

                    val code =
                        connection.responseCode

                    val elapsed =
                        System.currentTimeMillis()
                        - started

                    diagnostics.stage(
                        "SERVICE_TEST_RESPONSE",
                        "HTTP $code " +
                        "latencyMs=$elapsed " +
                        "contentType=${connection.contentType}"
                    )

                    if (
                        code !in 200..399
                    ) {

                        throw diagnostics.fail(
                            DiagnosticCategory.SERVICE_ERROR,
                            "SERVICE_TEST_HTTP",
                            "External Space returned HTTP $code"
                        )
                    }

                    runOnUiThread {

                        setControlsEnabled(true)

                        progressBar.progress = 100

                        statusLabel.text =
                            "AI service is reachable " +
                            "(HTTP $code, ${elapsed}ms)."

                        updateDiagnosticSummary()
                    }

                } finally {

                    connection.disconnect()
                }

            } catch (
                e: Exception
            ) {

                val snapshot =
                    diagnostics.capture(
                        e,
                        "SERVICE_TEST"
                    )

                runOnUiThread {

                    setControlsEnabled(true)

                    progressBar.progress = 0

                    showDiagnosticFailure(
                        snapshot,
                        "AI service test failed"
                    )
                }
            }
        }
    }

    private fun copyDiagnostics() {

        val report =
            diagnostics.latestReport()

        val clipboard =
            getSystemService(
                android.content.ClipboardManager::class.java
            )

        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText(
                "Kids AI Video Creator diagnostics",
                report
            )
        )

        Toast.makeText(
            this,
            "Diagnostics copied to clipboard",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun clearFailedGeneration() {

        transformer?.cancel()

        transformer = null

        videoPreview.stopPlayback()

        generatedVideoFile?.delete()

        generatedVideoFile = null

        storySceneFiles.forEach {
            it.delete()
        }

        storySceneFiles.clear()

        storyCacheKey = null

        cacheDir
            .listFiles()
            ?.filter {

                it.name.startsWith(
                    "ai_generated_"
                ) ||
                it.name.startsWith(
                    "story_"
                ) ||
                it.name.startsWith(
                    "story_joined_"
                ) ||
                it.name.startsWith(
                    "kids_video_mixed_"
                )
            }
            ?.forEach {
                it.delete()
            }

        diagnostics.clear()

        progressBar.progress = 0

        statusLabel.text =
            "Ready — failed generation state cleared."

        diagnosticLabel.text =
            "Diagnostics cleared."

        modeInfo.text =
            "Single Clip keeps the original proven generation path. " +
            "Story Mode uses sequential 5-second scenes."

        exportButton.isEnabled = false

        setControlsEnabled(true)

        Toast.makeText(
            this,
            "Failed generation state cleared",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showDiagnosticFailure(
        snapshot: DiagnosticSnapshot,
        prefix: String
    ) {

        statusLabel.text =
            "$prefix — ${snapshot.userMessage}"

        diagnosticLabel.text =
            snapshot.summary

        Toast.makeText(
            this,
            "${snapshot.category} at ${snapshot.stage}",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun updateDiagnosticSummary() {

        runOnUiThread {

            diagnosticLabel.text =
                diagnostics.summary()
        }
    }

    private fun setBusy(busy: Boolean, message: String, progress: Int) {
        setControlsEnabled(!busy)
        statusLabel.text = message
        progressBar.progress = progress
    }

    private fun setControlsEnabled(enabled: Boolean) {
        promptInput.isEnabled = enabled
        modeSpinner.isEnabled = enabled
        styleSpinner.isEnabled = enabled
        aspectSpinner.isEnabled = enabled
        durationSpinner.isEnabled = enabled
        audioButton.isEnabled = enabled
        generateButton.isEnabled = enabled

        testServiceButton.isEnabled = enabled
        clearGenerationButton.isEnabled = enabled
        copyDiagnosticsButton.isEnabled = true

        exportButton.isEnabled =
            enabled &&
            generatedVideoFile?.exists() == true
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
