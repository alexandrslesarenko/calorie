package com.calorie.app.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.NotFoundException
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
import com.calorie.app.logic.Provider
import com.calorie.app.logic.Recognizer
import java.time.Duration
import java.util.Base64

/**
 * Why recognition failed; the UI shows its own text for each reason.
 * retryable - the same request is likely to succeed later, so it goes to the retry queue.
 */
enum class AiFailure(val retryable: Boolean) {
    /** The key was rejected (401) or has no access to the model (403). */
    BAD_KEY(false),
    /** The Console balance is empty. */
    NO_CREDIT(false),
    /** Too many requests (429). */
    RATE_LIMIT(true),
    /** The service is overloaded or failed on its side (5xx, 529). */
    OVERLOADED(true),
    /** No network or the connection dropped. */
    NETWORK(true),
    /** The model refused or the answer does not match the schema. */
    BAD_ANSWER(false),
    /**
     * The model is not served (404): retired or never existed. Not retried - the next
     * recognizer in the list is asked instead.
     */
    MODEL_GONE(false),
    /**
     * The free daily quota is used up (Gemini). The next recognizer with a key is asked at once;
     * with none, the request waits in the queue for the quota to come back.
     */
    QUOTA_DAY(true);

    /** The next recognizer may answer instead: this one cannot for now, whatever is sent. */
    val skipsToNext: Boolean get() = this == MODEL_GONE || this == QUOTA_DAY

    companion object {
        fun byName(n: String?) = entries.firstOrNull { it.name == n }
    }
}

class AiException(val failure: AiFailure, message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
    /** Which recognizer failed, when known: a rejected key or a gone model is shown with its name. */
    var by: Recognizer? = null
}

/** Tokens spent on a request: for the spending counter in settings. */
data class AiUsage(val inputTokens: Long, val outputTokens: Long)

/** json - the raw tool input: this is what goes into the answer cache. */
data class AiResponse(val result: AiResult, val usage: AiUsage, val json: String)

/** A food recognizer behind one API: blocking calls, background threads only. */
interface FoodRecognizer : AutoCloseable {
    /** Food photo (JPEG, already downscaled) with an optional user hint. */
    fun recognizePhoto(jpeg: ByteArray, hint: String?, language: String): AiResponse

    /** Food described in words ("a bowl of borscht and two slices of bread"). */
    fun recognizeText(description: String, language: String): AiResponse

    /** Key check without spending tokens: fetches the model description. */
    fun checkKey()

    companion object {
        fun create(r: Recognizer, apiKey: String): FoodRecognizer = when (r.provider) {
            Provider.CLAUDE -> ClaudeFood(apiKey, model = r.model)
            Provider.GEMINI -> GeminiFood(apiKey, model = r.model)
        }
    }
}

/** Instructions shared by all recognizers, so they estimate the same way. */
object FoodPrompt {
    /** answer - how to deliver the result; the last line sets the answer language. */
    fun system(answer: String, language: String): String = """
        You are a nutrition assistant in a calorie counting app for weight loss.
        Identify every distinct food and drink the user shows or describes and estimate each portion.
        - Estimate the portion weight in grams from visual cues: plate and cutlery size, packaging, typical servings. For drinks, 1 ml counts as 1 g.
        - Give kcal, protein, fat and carbohydrates for that whole portion, not per 100 g.
        - Assume a typical home or restaurant recipe, including visible oil, sauces and dressings.
        - If a nutrition label is visible, use its values and scale them to the portion.
        - Use the user's hint (portion size, cooking method) when given; it overrides your visual estimate.
        - Split a meal into separate items when their calories differ a lot (for example steak, fries, salad), but keep a dish that is eaten as one thing as one item (for example borscht with sour cream).
        - Name each item the way a person writes it in a food diary: 1-3 words, at most 25 characters (12 for Chinese, Japanese and Korean). Leave brand, cooking method, ingredients and portion size out of the name unless they change what the dish is; put anything important into note.
        - confidence: high when the food and portion are clear, low when you are guessing.
        - If there is no food, return an empty items list and explain briefly in note.
        $answer Write item names and the note in $language.
    """.trimIndent()

    /** Schema description of an item name. The name has to fit the one-line field on the confirm screen. */
    const val NAME = "Short dish name, 1-3 words, at most 25 characters"

    fun photoText(hint: String?) = if (hint.isNullOrBlank()) "What food is on this photo?" else "What food is on this photo? My hint: $hint"

    fun descriptionText(description: String) = "I ate: $description"
}

/**
 * Food recognition with a Claude model (Haiku 5.5 by default). The answer comes as a
 * report_food tool call with a schema: the model has to fill it in (tool_choice forces this
 * tool), so there is no free text to parse. Forced tool use works on Haiku 4.5 and 5.5;
 * Sonnet and Opus 5.5 reject it with a 400 and would need another request shape.
 *
 * baseUrl is set in tests (a local MockWebServer); the app uses the default address.
 */
class ClaudeFood(apiKey: String, baseUrl: String? = null, private val model: String = MODEL) : FoodRecognizer {
    companion object {
        const val MODEL = "claude-haiku-5-5"
        const val TOOL = "report_food"
        /** A list of several dishes with macros fits with room to spare. */
        const val MAX_TOKENS = 2048L
        private val TIMEOUT = Duration.ofSeconds(60)

        fun systemPrompt(language: String): String = FoodPrompt.system("Always answer by calling the $TOOL tool.", language)

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
                                                "name" to mapOf("type" to "string", "description" to FoodPrompt.NAME),
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

    override fun recognizePhoto(jpeg: ByteArray, hint: String?, language: String): AiResponse {
        val image = ImageBlockParam.builder()
            .source(
                Base64ImageSource.builder()
                    .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                    .data(Base64.getEncoder().encodeToString(jpeg))
                    .build()
            )
            .build()
        val text = FoodPrompt.photoText(hint)
        return ask(
            listOf(
                ContentBlockParam.ofImage(image),
                ContentBlockParam.ofText(TextBlockParam.builder().text(text).build()),
            ),
            language,
        )
    }

    override fun recognizeText(description: String, language: String): AiResponse =
        ask(listOf(ContentBlockParam.ofText(TextBlockParam.builder().text(FoodPrompt.descriptionText(description)).build())), language)

    override fun checkKey() {
        call { client.models().retrieve(model) }
    }

    private fun ask(content: List<ContentBlockParam>, language: String): AiResponse {
        val params = MessageCreateParams.builder()
            .model(model)
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
    } catch (e: NotFoundException) {
        // The documented 404 is "resource not found"; for a fixed endpoint that is the model.
        throw AiException(AiFailure.MODEL_GONE, e.message, e)
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
