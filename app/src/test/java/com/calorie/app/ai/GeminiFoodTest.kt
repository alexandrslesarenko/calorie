package com.calorie.app.ai

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * The Gemini client against a local server: what is sent (field names as in Google's shell
 * examples) and how answers and errors are read. Costs nothing, but cannot prove that Google
 * accepts the body - only a real request does.
 */
class GeminiFoodTest {
    private lateinit var server: MockWebServer
    private lateinit var ai: GeminiFood

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        ai = GeminiFood("AIza-test", server.url("/").toString())
    }

    @After
    fun tearDown() {
        ai.close()
        server.shutdown()
    }

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun answer(text: String, finish: String = "STOP") = json(
        200,
        JSONObject()
            .put(
                "candidates",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("content", JSONObject().put("role", "model").put("parts", org.json.JSONArray().put(JSONObject().put("text", text))))
                        .put("finishReason", finish)
                )
            )
            .put("usageMetadata", JSONObject().put("promptTokenCount", 1400).put("candidatesTokenCount", 120).put("thoughtsTokenCount", 30))
            .toString(),
    )

    private fun error(code: Int, status: String, message: String, reason: String? = null) = json(
        code,
        """{"error":{"code":$code,"message":"$message","status":"$status"${reason?.let { ""","details":[{"reason":"$it"}]""" } ?: ""}}}""",
    )

    private fun expectFailure(f: AiFailure, block: () -> Unit) {
        try {
            block()
            fail("no exception")
        } catch (e: AiException) {
            assertEquals(f, e.failure)
        }
    }

    @Test
    fun photoRequestAndAnswer() {
        server.enqueue(
            answer("""{"items":[{"name":"Гречка","grams":200,"kcal":220,"protein_g":8,"fat_g":2,"carbs_g":44,"confidence":"high"}],"note":"ok"}""")
        )
        val jpeg = byteArrayOf(-1, -40, -1, 1, 2, 3)
        val r = ai.recognizePhoto(jpeg, "порция 200 г", "Russian")
        assertEquals("Гречка", r.result.items.single().draft.name)
        assertEquals(220.0, r.result.items.single().draft.total.kcal, 0.01)
        assertEquals(1400L, r.usage.inputTokens)
        // Thinking is paid output too.
        assertEquals(150L, r.usage.outputTokens)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1beta/models/gemini-3.5-flash-lite:generateContent", req.path)
        assertEquals("AIza-test", req.getHeader("x-goog-api-key"))
        val body = JSONObject(req.body.readUtf8())
        assertTrue(body.getJSONObject("system_instruction").getJSONArray("parts").getJSONObject(0).getString("text").endsWith("Russian."))
        val parts = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        val image = parts.getJSONObject(0).getJSONObject("inline_data")
        assertEquals("image/jpeg", image.getString("mime_type"))
        assertEquals(Base64.getEncoder().encodeToString(jpeg), image.getString("data"))
        assertTrue(parts.getJSONObject(1).getString("text").contains("порция 200 г"))
        val config = body.getJSONObject("generationConfig")
        assertEquals("application/json", config.getString("response_mime_type"))
        val schema = config.getJSONObject("response_schema")
        assertEquals("OBJECT", schema.getString("type"))
        val items = schema.getJSONObject("properties").getJSONObject("items")
        assertEquals("ARRAY", items.getString("type"))
        val confidence = items.getJSONObject("items").getJSONObject("properties").getJSONObject("confidence")
        assertEquals("enum", confidence.getString("format"))
        // Nothing undocumented: no thinking settings.
        assertFalse(config.has("thinkingConfig"))
    }

    @Test
    fun thoughtPartsIgnored() {
        val body = JSONObject()
            .put(
                "candidates",
                org.json.JSONArray().put(
                    JSONObject().put("finishReason", "STOP").put(
                        "content",
                        JSONObject().put(
                            "parts",
                            org.json.JSONArray()
                                .put(JSONObject().put("text", "thinking about soup").put("thought", true))
                                .put(JSONObject().put("text", """{"items":[],"note":"no food"}""")),
                        ),
                    )
                )
            )
        server.enqueue(json(200, body.toString()))
        assertEquals("no food", ai.recognizeText("стол", "English").result.note)
    }

    @Test
    fun cutOrBlockedAnswerRejected() {
        server.enqueue(answer("""{"items":[{"name":"Су""", finish = "MAX_TOKENS"))
        expectFailure(AiFailure.BAD_ANSWER) { ai.recognizeText("суп", "English") }
        server.enqueue(json(200, """{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        expectFailure(AiFailure.BAD_ANSWER) { ai.recognizeText("суп", "English") }
    }

    @Test
    fun badKey() {
        server.enqueue(error(400, "INVALID_ARGUMENT", "API key not valid. Please pass a valid API key.", "API_KEY_INVALID"))
        expectFailure(AiFailure.BAD_KEY) { ai.recognizeText("суп", "English") }
        server.enqueue(error(403, "PERMISSION_DENIED", "Permission denied"))
        expectFailure(AiFailure.BAD_KEY) { ai.checkKey() }
    }

    @Test
    fun otherBadRequestIsNotTheKey() {
        server.enqueue(error(400, "FAILED_PRECONDITION", "User location is not supported for the API use."))
        try {
            ai.recognizeText("суп", "English")
            fail("no exception")
        } catch (e: AiException) {
            assertEquals(AiFailure.BAD_ANSWER, e.failure)
            assertEquals("User location is not supported for the API use.", e.message)
        }
    }

    @Test
    fun goneBusyAndDown() {
        server.enqueue(error(404, "NOT_FOUND", "models/gemini-3.5-flash-lite is not found"))
        expectFailure(AiFailure.MODEL_GONE) { ai.recognizeText("суп", "English") }
        server.enqueue(error(429, "RESOURCE_EXHAUSTED", "Quota exceeded"))
        expectFailure(AiFailure.RATE_LIMIT) { ai.recognizeText("суп", "English") }
        // As reported by developers: the details name the violated quota.
        server.enqueue(
            json(
                429,
                """{"error":{"code":429,"message":"You exceeded your current quota. Please retry in 27.9s.","status":"RESOURCE_EXHAUSTED",
                "details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaMetric":"generativelanguage.googleapis.com/generate_content_free_tier_requests",
                "quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier","quotaValue":"20"}]}]}}""",
            )
        )
        expectFailure(AiFailure.QUOTA_DAY) { ai.recognizeText("суп", "English") }
        server.enqueue(error(503, "UNAVAILABLE", "The model is overloaded"))
        expectFailure(AiFailure.OVERLOADED) { ai.recognizeText("суп", "English") }
    }

    @Test
    fun checkKeyAsksForTheModel() {
        server.enqueue(json(200, """{"name":"models/gemini-3.5-flash-lite"}"""))
        ai.checkKey()
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/v1beta/models/gemini-3.5-flash-lite", req.path)
    }

    @Test
    fun networkFailure() {
        server.shutdown()
        expectFailure(AiFailure.NETWORK) { ai.recognizeText("суп", "English") }
    }
}
