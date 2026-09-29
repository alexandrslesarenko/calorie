package com.calorie.app.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolChoiceTool
import com.calorie.app.logic.AiParser
import com.calorie.app.logic.AiResult
import java.time.Duration
import java.util.Base64

/** Why recognition failed; the UI shows its own text for each reason. */
enum class AiFailure {
    /** The key was rejected (401) or has no access to the model (403). */
    BAD_KEY,
    /** The Console balance is empty. */
    NO_CREDIT,
    /** Too many requests (429). */
    RATE_LIMIT,
    /** The service is overloaded or failed on its side (5xx, 529). */
    OVERLOADED,
    /** No network or the connection dropped. */
    NETWORK,
    /** The model refused or the answer does not match the schema. */
    BAD_ANSWER,
}

class AiException(val failure: AiFailure, message: String? = null, cause: Throwable? = null) : Exception(message, cause)

/** Tokens spent on a request: for the spending counter in settings. */
data class AiUsage(val inputTokens: Long, val outputTokens: Long)

/** json - the raw tool input: this is what goes into the answer cache. */
data class AiResponse(val result: AiResult, val usage: AiUsage, val json: String)

/**
 * Food recognition with Claude Haiku 4.5. The answer comes as a report_food tool call
 * with a schema: the model has to fill it in (tool_choice forces this tool), so there is no
 * free text to parse. Blocking calls - background threads only.
 *
 * baseUrl is set in tests (a local MockWebServer); the app uses the default address.
 */
class ClaudeFood(apiKey: String, baseUrl: String? = null) : AutoCloseable {
    companion object {
        const val MODEL = "claude-haiku-4-5"
        const val TOOL = "report_food"
        /** A list of several dishes with macros fits with room to spare. */
        const val MAX_TOKENS = 2048L
        private val TIMEOUT = Duration.ofSeconds(60)

        /** System prompt; the last line sets the answer language. */
        fun systemPrompt(language: String): String = """
            You are a nutrition assistant in a calorie counting app for weight loss.
            Identify every distinct food and drink the user shows or describes and estimate each portion.
            - Estimate the portion weight in grams from visual cues: plate and cutlery size, packaging, typical servings. For drinks, 1 ml counts as 1 g.
            - Give kcal, protein, fat and carbohydrates for that whole portion, not per 100 g.
            - Assume a typical home or restaurant recipe, including visible oil, sauces and dressings.
            - If a nutrition label is visible, use its values and scale them to the portion.
            - Use the user's hint (portion size, cooking method) when given; it overrides your visual estimate.
            - Split a meal into separate items when their calories differ a lot (for example steak, fries, salad), but keep a dish that is eaten as one thing as one item (for example borscht with sour cream).
            - confidence: high when the food and portion are clear, low when you are guessing.
            - If there is no food, return an empty items list and explain briefly in note.
            Always answer by calling the $TOOL tool. Write item names and the note in $language.
        """.trimIndent()

        private fun num(desc: String) = mapOf("type" to "number", "description" to desc)

        val tool: Tool = Tool.builder()
            .name(TOOL)
            .description("Report the recognized foods with portion weight and nutrition for the whole portion.")
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty(
                                "items",
                                JsonValue.from(
                                    mapOf(
                                        "type" to "array",
                                        "items" to mapOf(
                                            "type" to "object",
                                            "properties" to mapOf(
                                                "name" to mapOf("type" to "string", "description" to "Short dish name"),
                                                "grams" to num("Estimated portion weight, g"),
                                                "kcal" to num("Energy of the whole portion, kcal"),
                                                "protein_g" to num("Protein in the portion, g"),
                                                "fat_g" to num("Fat in the portion, g"),
                                                "carbs_g" to num("Carbohydrates in the portion, g"),
                                                "confidence" to mapOf("type" to "string", "enum" to listOf("low", "medium", "high")),
                                            ),
                                            "required" to listOf("name", "grams", "kcal", "protein_g", "fat_g", "carbs_g", "confidence"),
                                        ),
                                    )
                                ),
                            )
                            .putAdditionalProperty(
                                "note",
                                JsonValue.from(mapOf("type" to "string", "description" to "Optional short remark for the user")),
                            )
                            .build()
                    )
                    .required(listOf("items"))
                    .build()
            )
            .build()
    }

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { if (baseUrl != null) baseUrl(baseUrl) }
        .timeout(TIMEOUT)
        // The SDK retries 429/5xx itself; more than one retry makes the user wait too long.
        .maxRetries(1)
        .build()

    /** Food photo (JPEG, already downscaled) with an optional user hint. */
    fun recognizePhoto(jpeg: ByteArray, hint: String?, language: String): AiResponse {
        val image = ImageBlockParam.builder()
            .source(
                Base64ImageSource.builder()
                    .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                    .data(Base64.getEncoder().encodeToString(jpeg))
                    .build()
            )
            .build()
        val text = if (hint.isNullOrBlank()) "What food is on this photo?" else "What food is on this photo? My hint: $hint"
        return ask(
            listOf(
                ContentBlockParam.ofImage(image),
                ContentBlockParam.ofText(TextBlockParam.builder().text(text).build()),
            ),
            language,
        )
    }

    /** Food described in words ("a bowl of borscht and two slices of bread"). */
    fun recognizeText(description: String, language: String): AiResponse =
        ask(listOf(ContentBlockParam.ofText(TextBlockParam.builder().text("I ate: $description").build())), language)

    /** Key check without spending tokens: fetches the model description. */
    fun checkKey() {
        call { client.models().retrieve(MODEL) }
    }

    private fun ask(content: List<ContentBlockParam>, language: String): AiResponse {
        val params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(MAX_TOKENS)
            .system(systemPrompt(language))
            .addTool(tool)
            .toolChoice(ToolChoiceTool.builder().name(TOOL).build())
            .addUserMessageOfBlockParams(content)
            .build()
        val msg = call { client.messages().create(params) }
        val usage = AiUsage(msg.usage().inputTokens(), msg.usage().outputTokens())
        val stop = msg.stopReason().orElse(null)
        // JSON cut off by max_tokens cannot be parsed, and a refusal even less so.
        if (stop == StopReason.MAX_TOKENS || stop == StopReason.REFUSAL) throw AiException(AiFailure.BAD_ANSWER, stop.toString())
        val input = msg.content().firstNotNullOfOrNull { it.toolUse().orElse(null)?.takeIf { t -> t.name() == TOOL } }
            ?: throw AiException(AiFailure.BAD_ANSWER, "no tool call")
        val json = jsonMapper().writeValueAsString(input._input())
        val result = runCatching { AiParser.parse(json) }.getOrElse { throw AiException(AiFailure.BAD_ANSWER, it.message, it) }
        return AiResponse(result, usage, json)
    }

    /** SDK errors -> reasons the screen can explain. */
    private fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: UnauthorizedException) {
        throw AiException(AiFailure.BAD_KEY, e.message, e)
    } catch (e: PermissionDeniedException) {
        throw AiException(AiFailure.BAD_KEY, e.message, e)
    } catch (e: RateLimitException) {
        throw AiException(AiFailure.RATE_LIMIT, e.message, e)
    } catch (e: AnthropicServiceException) {
        val failure = when {
            e.statusCode() >= 500 -> AiFailure.OVERLOADED
            // An empty balance is a 400 mentioning credit balance.
            e.statusCode() == 400 && e.body().toString().contains("credit balance", ignoreCase = true) -> AiFailure.NO_CREDIT
            else -> AiFailure.BAD_ANSWER
        }
        throw AiException(failure, e.message, e)
    } catch (e: AnthropicIoException) {
        throw AiException(AiFailure.NETWORK, e.message, e)
    }

    override fun close() = client.close()
}
