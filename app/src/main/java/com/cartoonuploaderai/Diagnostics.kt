package com.cartoonuploaderai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

enum class DiagnosticCategory {
    SERVICE_ERROR,
    QUEUE_ERROR,
    NETWORK_ERROR,
    API_VALIDATION_ERROR,
    RESULT_PARSE_ERROR,
    DOWNLOAD_ERROR,
    STITCH_ERROR,
    EXPORT_ERROR,
    APP_ERROR
}

class DiagnosticFailure(
    val category: DiagnosticCategory,
    val stage: String,
    val detail: String,
    cause: Throwable? = null
) : Exception(detail, cause)

data class DiagnosticSnapshot(
    val category: DiagnosticCategory,
    val stage: String,
    val userMessage: String,
    val summary: String,
    val report: String
)

fun diagSnippet(value: String?, max: Int = 500): String {
    if (value.isNullOrBlank()) return "(empty)"
    return value
        .replace("\n", " ")
        .replace("\r", " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(max)
}

class DiagnosticCenter(
    private val context: Context,
    private val endpoint: String
) {
    private val prefs =
        context.getSharedPreferences("kids_ai_diagnostics", Context.MODE_PRIVATE)

    private val lines = mutableListOf<String>()

    private var startedAt = System.currentTimeMillis()
    private var mode = "IDLE"
    private var scene = "n/a"
    private var eventId: String? = null
    private var lastStage = "READY"
    private var lastDetail = "No diagnostic event yet."

    private var persistedFailure =
        prefs.getString("last_failure_report", "").orEmpty()

    @Synchronized
    fun begin(newMode: String) {
        lines.clear()
        startedAt = System.currentTimeMillis()
        mode = newMode
        scene = "n/a"
        eventId = null
        lastStage = "START"
        lastDetail = "mode=$newMode"
        addLine("START", "mode=$newMode")
    }

    @Synchronized
    fun stage(stage: String, detail: String = "") {
        lastStage = stage
        lastDetail = detail.ifBlank { "stage entered" }
        addLine(stage, lastDetail)
    }

    @Synchronized
    fun setScene(index: Int, total: Int) {
        scene = "$index/$total"
        addLine("SCENE", scene)
    }

    @Synchronized
    fun setEventId(value: String) {
        eventId = value
        addLine("EVENT_ID", value)
    }

    fun fail(
        category: DiagnosticCategory,
        stage: String,
        detail: String,
        cause: Throwable? = null
    ): DiagnosticFailure =
        DiagnosticFailure(category, stage, detail, cause)

    fun asFailure(stage: String, error: Throwable): DiagnosticFailure {
        if (error is DiagnosticFailure) return error

        return DiagnosticFailure(
            classify(error),
            stage,
            "${error.javaClass.simpleName}: ${error.message ?: "(no message)"}",
            error
        )
    }

    @Synchronized
    fun capture(error: Throwable, fallbackStage: String): DiagnosticSnapshot {
        val failure =
            if (error is DiagnosticFailure) error
            else asFailure(fallbackStage, error)

        lastStage = failure.stage
        lastDetail = "${failure.category}: ${failure.detail}"

        addLine(
            "FAILURE",
            "category=${failure.category} stage=${failure.stage} detail=${failure.detail}"
        )

        val report = buildReport(failure)
        persistedFailure = report

        prefs.edit()
            .putString("last_failure_report", report)
            .apply()

        val userMessage = when (failure.category) {
            DiagnosticCategory.SERVICE_ERROR ->
                "The external AI service failed or returned a server-side error."

            DiagnosticCategory.QUEUE_ERROR ->
                "The external AI queue is busy, rejected the job, or ended unexpectedly."

            DiagnosticCategory.NETWORK_ERROR ->
                "The phone lost or could not establish a connection to the AI service."

            DiagnosticCategory.API_VALIDATION_ERROR ->
                "The AI service rejected the request parameters."

            DiagnosticCategory.RESULT_PARSE_ERROR ->
                "The service responded, but the app could not find a usable video result."

            DiagnosticCategory.DOWNLOAD_ERROR ->
                "The AI result was returned, but the MP4 download failed."

            DiagnosticCategory.STITCH_ERROR ->
                "The AI scenes generated, but local scene stitching failed."

            DiagnosticCategory.EXPORT_ERROR ->
                "Generation succeeded, but audio mixing or saving to the phone failed."

            DiagnosticCategory.APP_ERROR ->
                "The Android app hit an unexpected internal error."
        }

        return DiagnosticSnapshot(
            category = failure.category,
            stage = failure.stage,
            userMessage = userMessage,
            summary =
                "${failure.category} @ ${failure.stage}\n" +
                "Scene: $scene\n" +
                diagSnippet(failure.detail, 260),
            report = report
        )
    }

    @Synchronized
    fun summary(): String =
        "Stage: $lastStage\nScene: $scene\n${diagSnippet(lastDetail, 260)}"

    @Synchronized
    fun restoredSummary(): String =
        if (persistedFailure.isNotBlank()) {
            "A previous failure report is saved. Tap Copy Diagnostics to retrieve it."
        } else {
            "No diagnostic failure recorded yet."
        }

    @Synchronized
    fun latestReport(): String =
        if (lines.isNotEmpty()) buildReport(null)
        else if (persistedFailure.isNotBlank()) persistedFailure
        else buildReport(null)

    @Synchronized
    fun clear() {
        lines.clear()
        startedAt = System.currentTimeMillis()
        mode = "IDLE"
        scene = "n/a"
        eventId = null
        lastStage = "READY"
        lastDetail = "Diagnostics cleared."
        persistedFailure = ""

        prefs.edit()
            .remove("last_failure_report")
            .apply()
    }

    private fun classify(error: Throwable): DiagnosticCategory {
        var current: Throwable? = error

        while (current != null) {
            when (current) {
                is SocketTimeoutException,
                is UnknownHostException,
                is ConnectException,
                is SSLException ->
                    return DiagnosticCategory.NETWORK_ERROR

                is IOException ->
                    return DiagnosticCategory.NETWORK_ERROR
            }

            current = current.cause
        }

        val text = generateSequence(error) { it.cause }
            .joinToString(" ") {
                "${it.javaClass.simpleName} ${it.message.orEmpty()}"
            }

        return when {
            text.contains("429") ||
                text.contains("queue", ignoreCase = true) ||
                text.contains("busy", ignoreCase = true) ->
                DiagnosticCategory.QUEUE_ERROR

            text.contains("400") ||
                text.contains("422") ||
                text.contains("validation", ignoreCase = true) ->
                DiagnosticCategory.API_VALIDATION_ERROR

            text.contains("download", ignoreCase = true) ->
                DiagnosticCategory.DOWNLOAD_ERROR

            else ->
                DiagnosticCategory.APP_ERROR
        }
    }

    private fun addLine(stage: String, detail: String) {
        val elapsed = System.currentTimeMillis() - startedAt

        lines.add(
            "+${elapsed}ms [$stage] ${diagSnippet(detail, 900)}"
        )

        while (lines.size > 120) {
            lines.removeAt(0)
        }
    }

    private fun networkStatus(): String {
        return try {
            val manager =
                context.getSystemService(ConnectivityManager::class.java)

            val network =
                manager.activeNetwork ?: return "disconnected"

            val capabilities =
                manager.getNetworkCapabilities(network)
                    ?: return "unknown"

            val internet =
                capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                )

            val validated =
                capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED
                )

            val transports = mutableListOf<String>()

            if (
                capabilities.hasTransport(
                    NetworkCapabilities.TRANSPORT_WIFI
                )
            ) {
                transports += "wifi"
            }

            if (
                capabilities.hasTransport(
                    NetworkCapabilities.TRANSPORT_CELLULAR
                )
            ) {
                transports += "cellular"
            }

            if (
                capabilities.hasTransport(
                    NetworkCapabilities.TRANSPORT_ETHERNET
                )
            ) {
                transports += "ethernet"
            }

            if (
                capabilities.hasTransport(
                    NetworkCapabilities.TRANSPORT_VPN
                )
            ) {
                transports += "vpn"
            }

            "internet=$internet validated=$validated transport=${
                transports.joinToString(",").ifBlank { "other" }
            }"

        } catch (e: Exception) {
            "network-check-error=${e.javaClass.simpleName}"
        }
    }

    private fun exceptionChain(error: Throwable?): String {
        if (error == null) return "none"

        return generateSequence(error) { it.cause }
            .take(6)
            .joinToString(" -> ") {
                "${it.javaClass.name}: ${
                    it.message ?: "(no message)"
                }"
            }
    }

    private fun buildReport(
        failure: DiagnosticFailure?
    ): String =
        buildString {
            appendLine(
                "Kids AI Video Creator - Diagnostic Report"
            )

            appendLine("mode=$mode")
            appendLine("endpoint=$endpoint")
            appendLine("network=${networkStatus()}")
            appendLine("scene=$scene")
            appendLine("eventId=${eventId ?: "none"}")
            appendLine(
                "elapsedMs=${
                    System.currentTimeMillis() - startedAt
                }"
            )
            appendLine("lastStage=$lastStage")

            if (failure != null) {
                appendLine(
                    "category=${failure.category}"
                )
                appendLine(
                    "failureStage=${failure.stage}"
                )
                appendLine(
                    "detail=${failure.detail}"
                )
                appendLine(
                    "exceptionChain=${exceptionChain(failure)}"
                )
            }

            appendLine()
            appendLine("Timeline:")

            lines.forEach {
                appendLine(it)
            }
        }
}
