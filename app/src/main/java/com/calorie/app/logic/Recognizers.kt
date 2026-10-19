package com.calorie.app.logic

/** A recognizer passed over in a round, and why. */
class Skipped(val by: Recognizer, val error: Exception)

/**
 * Who answered a queued request instead of whom; why - AiFailure.name of the one passed over.
 * Kept as text in the detail of the ready row, so the review can say it.
 */
data class Fallback(val by: Recognizer, val from: Recognizer, val why: String) {
    fun encode() = "${by.key} ${from.key} $why"

    companion object {
        fun decode(s: String?): Fallback? {
            val p = s?.split(' ')?.takeIf { it.size == 3 } ?: return null
            val by = Recognizer.entries.firstOrNull { it.key == p[0] } ?: return null
            val from = Recognizer.entries.firstOrNull { it.key == p[1] } ?: return null
            return Fallback(by, from, p[2])
        }
    }
}

/** Answer of a round of recognizers: the value, who gave it, who was passed over. */
class Answered<T>(val value: T, val by: Recognizer, val skipped: List<Skipped>)

/**
 * Asks recognizers in order until one answers. A failure that skips (a retired model, a used
 * up daily quota, an overload repeated in the queue) moves on to the next, and onSkip is
 * told; any other failure ends the round: failed(r, e) is called and e rethrown. If all are
 * skipped, the first such failure is rethrown. order must not be empty.
 */
suspend fun <T> askInOrder(
    order: List<Recognizer>,
    skips: (Exception) -> Boolean,
    onSkip: (Recognizer, Exception) -> Unit,
    failed: (Recognizer, Exception) -> Unit,
    send: suspend (Recognizer) -> T,
): Answered<T> {
    require(order.isNotEmpty())
    val skipped = mutableListOf<Skipped>()
    for (r in order) {
        try {
            return Answered(send(r), r, skipped)
        } catch (e: Exception) {
            failed(r, e)
            if (!skips(e)) throw e
            onSkip(r, e)
            skipped += Skipped(r, e)
        }
    }
    throw skipped.first().error
}

/** Who runs a recognizer: each provider has its own API key. */
enum class Provider { CLAUDE, GEMINI }

/**
 * Food recognizers the user can choose from. key is stored in Prefs - do not rename; the key
 * of a removed one (haiku-4-5) reads back as DEFAULT.
 * Prices in nano-dollars per token (whole numbers, see Pricing), paid list prices. Gemini on
 * its free tier costs nothing (freeTier): its price is shown as "what it would cost".
 */
enum class Recognizer(
    val key: String,
    val provider: Provider,
    val model: String,
    val label: String,
    val inputNano: Long,
    val outputNano: Long,
    val freeTier: Boolean = false,
) {
    HAIKU_5_5("haiku-5-5", Provider.CLAUDE, "claude-haiku-5-5", "Claude Haiku 5.5", 100, 500),
    GEMINI_LITE("gemini-flash-lite", Provider.GEMINI, "gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", 300, 2500, freeTier = true);

    companion object {
        val DEFAULT = HAIKU_5_5

        fun byKey(k: String?) = entries.firstOrNull { it.key == k } ?: DEFAULT

        /**
         * Order of tries for one request: the chosen recognizer, then the others in list order;
         * only those whose provider has a key. A fallback is used only when the one before it
         * is gone, out of its daily quota or overloaded again and again (AiQueue.ask).
         */
        fun order(chosen: Recognizer, hasKey: (Provider) -> Boolean): List<Recognizer> =
            (listOf(chosen) + entries.filter { it != chosen }).filter { hasKey(it.provider) }
    }
}
