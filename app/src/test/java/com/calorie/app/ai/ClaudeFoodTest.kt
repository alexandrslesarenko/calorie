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
 * The real SDK client against a local server: checks what is sent to the API
 * (model, image, answer schema) and how answers and errors are handled. Costs nothing.
 */
class ClaudeFoodTest {
    private lateinit var server: MockWebServer
    private lateinit var ai: ClaudeFood

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        ai = ClaudeFood("sk-ant-test", server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        ai.close()
        server.shutdown()
    }

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun message(input: String, stop: String = "tool_use") = json(
        200,
        """{"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-4-5",
            "content":[{"type":"tool_use","id":"toolu_1","name":"report_food","input":$input}],
            "stop_reason":"$stop","stop_sequence":null,"usage":{"input_tokens":1520,"output_tokens":180}}""",
    )

    private fun error(code: Int, type: String, msg: String) =
        json(code, """{"type":"error","error":{"type":"$type","message":"$msg"}}""")

    private fun expectFailure(f: AiFailure, block: () -> Unit) {
        try {
            block()
            fail("expected failure $f")
        } catch (e: AiException) {
            assertEquals(f, e.failure)
        }
    }

    @Test
    fun photoRequestAndAnswer() {
        server.enqueue(
            message(
                """{"items":[{"name":"Гречка","grams":200,"kcal":220,"protein_g":8,"fat_g":2,"carbs_g":44,"confidence":"high"},
                {"name":"Котлета","grams":90,"kcal":230,"protein_g":14,"fat_g":17,"carbs_g":6,"confidence":"medium"}],"note":""}"""
            )
        )
        val jpeg = byteArrayOf(-1, -40, -1, -32, 1, 2, 3)
        val r = ai.recognizePhoto(jpeg, "порция 200 г", "Russian")

        assertEquals(2, r.result.items.size)
        assertEquals("Гречка", r.result.items[0].draft.name)
        assertEquals(110.0, r.result.items[0].draft.per100.kcal, 0.01)
        assertEquals(AiUsage(1520, 180), r.usage)
        // The tool input goes to the cache and must parse again without a request.
        assertEquals(2, com.calorie.app.logic.AiParser.parse(r.json).items.size)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/messages", req.path)
        assertEquals("sk-ant-test", req.getHeader("x-api-key"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals(ClaudeFood.MODEL, body.getString("model"))
        // On Haiku 4.5 effort returns 400, and thinking is incompatible with a forced tool.
        assertFalse(body.has("thinking"))
        assertFalse(body.has("output_config"))
        assertEquals("tool", body.getJSONObject("tool_choice").getString("type"))
        assertEquals("report_food", body.getJSONObject("tool_choice").getString("name"))
        val schema = body.getJSONArray("tools").getJSONObject(0).getJSONObject("input_schema")
        assertEquals("object", schema.getString("type"))
        assertTrue(schema.getJSONObject("properties").has("items"))
        assertTrue(body.getString("system").endsWith("Russian."))
        val content = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        val src = content.getJSONObject(0).getJSONObject("source")
        assertEquals("base64", src.getString("type"))
        assertEquals("image/jpeg", src.getString("media_type"))
        assertEquals(Base64.getEncoder().encodeToString(jpeg), src.getString("data"))
        assertTrue(content.getJSONObject(1).getString("text").contains("порция 200 г"))
    }

    @Test
    fun textRequest() {
        server.enqueue(message("""{"items":[],"note":"Не понял, что за блюдо"}"""))
        val r = ai.recognizeText("что-то", "Russian")
        assertTrue(r.result.items.isEmpty())
        assertEquals("Не понял, что за блюдо", r.result.note)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("I ate: что-то", body.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test
    fun truncatedAnswerRejected() {
        server.enqueue(message("""{"items":[]}""", stop = "max_tokens"))
        expectFailure(AiFailure.BAD_ANSWER) { ai.recognizeText("суп", "English") }
    }

    @Test
    fun badKey() {
        server.enqueue(error(401, "authentication_error", "invalid x-api-key"))
        expectFailure(AiFailure.BAD_KEY) { ai.recognizeText("суп", "English") }
    }

    @Test
    fun noCredit() {
        server.enqueue(
            error(400, "invalid_request_error", "Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing to upgrade or purchase credits.")
        )
        expectFailure(AiFailure.NO_CREDIT) { ai.recognizeText("суп", "English") }
    }

    @Test
    fun overloadedAfterRetry() {
        // The SDK retries once by itself - return overloaded twice.
        repeat(2) { server.enqueue(error(529, "overloaded_error", "Overloaded").setHeader("retry-after-ms", "10")) }
        expectFailure(AiFailure.OVERLOADED) { ai.recognizeText("суп", "English") }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun rateLimit() {
        repeat(2) { server.enqueue(error(429, "rate_limit_error", "slow down").setHeader("retry-after-ms", "10")) }
        expectFailure(AiFailure.RATE_LIMIT) { ai.recognizeText("суп", "English") }
    }

    @Test
    fun checkKeyUsesModelsEndpoint() {
        server.enqueue(
            json(200, """{"type":"model","id":"claude-haiku-4-5","display_name":"Claude Haiku 4.5","created_at":"2025-10-15T00:00:00Z"}""")
        )
        ai.checkKey()
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/v1/models/claude-haiku-4-5", req.path)
    }

    @Test
    fun checkKeyRejected() {
        server.enqueue(error(401, "authentication_error", "invalid x-api-key"))
        expectFailure(AiFailure.BAD_KEY) { ai.checkKey() }
    }

    @Test
    fun networkFailure() {
        server.shutdown()
        expectFailure(AiFailure.NETWORK) { ai.recognizeText("суп", "English") }
    }
}
