package com.example.activity

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** The single model call this feature makes; an interface so tests can supply a canned reply. */
interface LlmClient {
    fun completeJson(system: String, user: String): String
}

/**
 * A minimal OpenAI chat-completions client. The whole feature needs one endpoint, so this is a plain
 * HTTP call rather than an SDK dependency.
 */
class OpenAiClient(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val baseUrl: String = DEFAULT_BASE_URL
) : LlmClient {
    private val gson = Gson()
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    /** Sends one prompt and returns the assistant's message, which the caller parses as JSON. */
    override fun completeJson(system: String, user: String): String {
        require(apiKey.isNotBlank()) { "Missing OpenAI API key" }
        val payload = JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", 0.2)
            add("response_format", JsonObject().apply { addProperty("type", "json_object") })
            add("messages", gson.toJsonTree(listOf(
                mapOf("role" to "system", "content" to system),
                mapOf("role" to "user", "content" to user)
            )))
        }
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/chat/completions"))
            .timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build()

        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw OpenAiException("Could not reach OpenAI: ${e.message ?: "network error"}", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw OpenAiException("The request was interrupted", e)
        }

        if (response.statusCode() != 200) throw OpenAiException(describe(response.statusCode(), response.body()))
        return content(response.body())
    }

    private fun content(body: String): String {
        val message = runCatching {
            JsonParser.parseString(body).asJsonObject
                .getAsJsonArray("choices")[0].asJsonObject
                .getAsJsonObject("message")
                .get("content").asString
        }.getOrNull()
        return message?.takeIf { it.isNotBlank() } ?: throw OpenAiException("OpenAI returned an empty response")
    }

    /** Turns an API error into something worth showing in the tool window. */
    private fun describe(status: Int, body: String): String {
        val detail = runCatching {
            JsonParser.parseString(body).asJsonObject.getAsJsonObject("error").get("message").asString
        }.getOrNull()
        return when (status) {
            401, 403 -> "OpenAI rejected the API key. Check it in Settings | Tools | Code Visualizer."
            404 -> "Model \"$model\" is not available for this key."
            429 -> "OpenAI rate limit or quota reached. ${detail.orEmpty()}".trim()
            in 500..599 -> "OpenAI is having trouble (HTTP $status). Try again in a moment."
            else -> detail ?: "OpenAI request failed (HTTP $status)"
        }
    }

    class OpenAiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    companion object {
        const val DEFAULT_MODEL = "gpt-4o-mini"
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    }
}
