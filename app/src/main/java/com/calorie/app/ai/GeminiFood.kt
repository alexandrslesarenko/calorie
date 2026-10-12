package com.calorie.app.ai

import com.calorie.app.logic.AiParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Food recognition with Gemini (generateContent REST, no SDK). The answer is JSON constrained by
 * response_schema with the same fields as Claude's report_food tool, so AiParser reads both.
 * Field names follow the documented shell examples: system_instruction, inline_data,
 * response_mime_type, response_schema with upper case types. On the free tier Google may use
 * what is sent to improve its products - the app says so next to the choice.
 *
 * baseUrl is set in tests (a local MockWebServer); the app uses the default address.
 */
class GeminiFood(
    private val apiKey: String,
    baseUrl: String = BASE_URL,
    private val model: String = MODEL,
) : FoodRecognizer {
    companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com"
        const val MODEL = "gemini-3.5-flash-lite"
        /** Thinking models spend output tokens on thinking too: leave room for it and the answer. */
        const val MAX_OUTPUT_TOKENS = 8192
        private val JSON = "application/json".toMediaType()

        private fun num(desc: String) = JSONObject().put("type", "NUMBER").put("description", desc)

        val schema: JSONObject = JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "items",
                        JSONObject().put("type", "ARRAY").put(
                            "items",
                            JSONObject()
                                .put("type", "OBJECT")
                                .put(
                                    "properties",
                                    JSONObject()
                                        .put("name", JSONObject().put("type", "STRING").put("description", "Short dish name"))
                                        .put("grams", num("Estimated portion weight, g"))
                                        .put("kcal", num("Energy of the whole portion, kcal"))
                                        .put("protein_g", num("Protein in the portion, g"))
                                        .put("fat_g", num("Fat in the portion, g"))
                                        .put("carbs_g", num("Carbohydrates in the portion, g"))
                                        // The reference ties enum to "Type.STRING with enum format".
                                        .put(
                                            "confidence",
                                            JSONObject().put("type", "STRING").put("format", "enum").put("enum", JSONArray(listOf("low", "medium", "high"))),
                                        ),
                                )
                                .put("required", JSONArray(listOf("name", "grams", "kcal", "protein_g", "fat_g", "carbs_g", "confidence"))),
                        )
                    )
                    .put("note", JSONObject().put("type", "STRING").put("description", "Optional short remark for the user")),
            )
            .put("required", JSONArray(listOf("items")))

        fun systemPrompt(language: String): String = FoodPrompt.system("Answer with JSON that matches the response schema.", language)
    }

    private val base = baseUrl.trimEnd('/')

    // The answer comes in one piece after the model is done (thinking included), and a photo
    // goes up over mobile data: OkHttp's 10 s read and write defaults are too short.
    private val http = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    override fun recognizePhoto(jpeg: ByteArray, hint: String?, language: String): AiResponse = generate(
        JSONArray()
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", Base64.getEncoder().encodeToString(jpeg))))
            .put(JSONObject().put("text", FoodPrompt.photoText(hint))),
        language,
    )

    override fun recognizeText(description: String, language: String): AiResponse =
        generate(JSONArray().put(JSONObject().put("text", FoodPrompt.descriptionText(description))), language)

    override fun checkKey() {
        send(Request.Builder().url("$base/v1beta/models/$model").header("x-goog-api-key", apiKey).get().build())
    }

    private fun generate(parts: JSONArray, language: String): AiResponse {
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt(language)))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put(
                "generationConfig",
                JSONObject()
                    .put("response_mime_type", "application/json")
                    .put("response_schema", schema)
                    .put("maxOutputTokens", MAX_OUTPUT_TOKENS),
            )
        val req = Request.Builder()
            .url("$base/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody(JSON))
            .build()
        val root = send(req)
        val usage = root.optJSONObject("usageMetadata")
        val aiUsage = AiUsage(
            usage?.optLong("promptTokenCount") ?: 0,
            (usage?.optLong("candidatesTokenCount") ?: 0) + (usage?.optLong("thoughtsTokenCount") ?: 0),
        )
        // A blocked prompt comes without candidates; a cut or filtered answer is not valid JSON.
        root.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotEmpty() }?.let {
            throw AiException(AiFailure.BAD_ANSWER, "blocked: $it")
        }
        val candidate = root.optJSONArray("candidates")?.optJSONObject(0) ?: throw AiException(AiFailure.BAD_ANSWER, "no candidates")
        val finish = candidate.optString("finishReason")
        if (finish.isNotEmpty() && finish != "STOP") throw AiException(AiFailure.BAD_ANSWER, finish)
        val partsOut = candidate.optJSONObject("content")?.optJSONArray("parts") ?: throw AiException(AiFailure.BAD_ANSWER, "no content")
        val text = (0 until partsOut.length())
            .mapNotNull { partsOut.optJSONObject(it) }
            .filter { !it.optBoolean("thought", false) }
            .joinToString("") { it.optString("text") }
        val result = runCatching { AiParser.parse(text) }.getOrElse { throw AiException(AiFailure.BAD_ANSWER, it.message, it) }
        return AiResponse(result, aiUsage, text)
    }

    /** Runs a request; HTTP errors -> reasons the screen can explain. */
    private fun send(req: Request): JSONObject {
        val resp: Response = try {
            http.newCall(req).execute()
        } catch (e: IOException) {
            throw AiException(AiFailure.NETWORK, e.message, e)
        }
        resp.use { r ->
            val text = try {
                r.body?.string().orEmpty()
            } catch (e: IOException) {
                throw AiException(AiFailure.NETWORK, e.message, e)
            }
            if (r.isSuccessful) {
                return try {
                    JSONObject(text)
                } catch (e: JSONException) {
                    throw AiException(AiFailure.BAD_ANSWER, e.message, e)
                }
            }
            val message = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull()?.ifEmpty { null } ?: "HTTP ${r.code}"
            val failure = when {
                r.code == 401 || r.code == 403 -> AiFailure.BAD_KEY
                // A wrong key is a 400 on this API, told apart by its reason.
                r.code == 400 && (text.contains("API_KEY_INVALID") || text.contains("API key not valid", ignoreCase = true)) -> AiFailure.BAD_KEY
                r.code == 404 -> AiFailure.MODEL_GONE
                // The violated quota is named in the details: a daily one does not pass in a minute,
                // although the answer still suggests retrying in seconds.
                r.code == 429 && text.contains("PerDay") -> AiFailure.QUOTA_DAY
                r.code == 429 -> AiFailure.RATE_LIMIT
                r.code >= 500 -> AiFailure.OVERLOADED
                else -> AiFailure.BAD_ANSWER
            }
            throw AiException(failure, message)
        }
    }

    override fun close() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}
