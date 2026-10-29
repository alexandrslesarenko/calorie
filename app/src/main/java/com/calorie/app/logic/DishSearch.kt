package com.calorie.app.logic

import kotlin.math.hypot
import kotlin.math.min

/**
 * Library search that forgives the typos of a small phone keyboard: a neighboring key instead
 * of the right one, a neighbor pressed together with it, a missed or doubled letter, two
 * letters swapped. Each query word has to match the start of some word of the name, in any
 * order. Fuzzy matches are shown only when nothing matches exactly, as before: otherwise "кофе"
 * would also bring every "конфета". The library is a few hundred dishes
 * at most, so a small edit distance per word on every keystroke costs nothing.
 */
object DishSearch {
    /** Wrong key next to the right one, or an extra press of a neighbor: the usual fat finger. */
    private const val NEAR = 0.5
    private const val FAR = 1.0
    /** A key far from the right one is not a slip of the finger: more than a short word allows. */
    private const val WRONG = 1.5

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** Rows of the layouts the dish names are typed on; Latin keys get neighbors from all three. */
    private val LAYOUTS = listOf(
        listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю"),
        listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
        listOf("qwertzuiop", "asdfghjkl", "yxcvbnm"),
        listOf("azertyuiop", "qsdfghjklm", "wxcvbn"),
    )
    /** Row shifts in key widths, as on a phone keyboard. */
    private val ROW_SHIFT = listOf(0.0, 0.5, 1.5)

    private val neighbors: Map<Char, Set<Char>> = buildMap<Char, MutableSet<Char>> {
        for (rows in LAYOUTS) {
            val keys = rows.flatMapIndexed { r, row -> row.mapIndexed { c, ch -> Triple(ch, c + ROW_SHIFT[r], r.toDouble()) } }
            for ((a, ax, ay) in keys) for ((b, bx, by) in keys) {
                // Next in the row is 1 key away, the keys above and below are within 1.5.
                if (a != b && hypot(ax - bx, ay - by) <= 1.5) getOrPut(a) { mutableSetOf() } += b
            }
        }
    }

    fun near(a: Char, b: Char): Boolean = neighbors[a]?.contains(b) == true

    /** Lowercase, "ё" as "е" and "ъ" as "ь" (they share a key), punctuation as spaces. */
    fun normalize(s: String): String =
        s.lowercase().replace('ё', 'е').replace('ъ', 'ь').replace(NON_WORD, " ").trim()

    /** Errors a query word may have: none for short ones, they would match everything. */
    fun budget(length: Int): Double = when {
        length <= 2 -> 0.0
        length == 3 -> NEAR
        length <= 6 -> 1.0
        length <= 9 -> 1.5
        else -> 2.0
    }

    /**
     * Cheapest edit of the query word into some start of the word (Damerau-Levenshtein,
     * restricted, with keyboard-aware costs).
     */
    fun prefixCost(q: String, w: String): Double {
        val n = q.length
        val m = w.length
        // d[i][j]: first i letters of the query against the first j of the word.
        val d = Array(n + 1) { DoubleArray(m + 1) }
        for (i in 1..n) d[i][0] = d[i - 1][0] + extraCost(q, i - 1)
        for (j in 1..m) d[0][j] = d[0][j - 1] + FAR
        for (i in 1..n) for (j in 1..m) {
            val a = q[i - 1]
            val b = w[j - 1]
            var v = minOf(
                d[i - 1][j - 1] + if (a == b) 0.0 else if (near(a, b)) NEAR else WRONG,
                d[i - 1][j] + extraCost(q, i - 1),
                d[i][j - 1] + FAR,
            )
            if (i > 1 && j > 1 && a == w[j - 2] && q[i - 2] == b) v = min(v, d[i - 2][j - 2] + FAR)
            d[i][j] = v
        }
        return (0..m).minOf { d[n][it] }
    }

    /** A letter typed that is not in the name: cheap if it doubles or sits next to a neighbor. */
    private fun extraCost(q: String, i: Int): Double {
        val c = q[i]
        val prev = q.getOrNull(i - 1)
        val next = q.getOrNull(i + 1)
        val slip = (prev != null && (prev == c || near(prev, c))) || (next != null && (next == c || near(next, c)))
        return if (slip) NEAR else FAR
    }

    /** Total cost of the query against the name; null - no match. 0 - an exact substring. */
    fun cost(query: String, name: String): Double? {
        val q = normalize(query)
        if (q.isEmpty()) return 0.0
        val n = normalize(name)
        if (q in n) return 0.0
        val words = n.split(' ')
        var total = 0.0
        for (qw in q.split(' ')) {
            // A word found inside a name word counts as exact, like the whole query before.
            val best = if (words.any { qw in it }) 0.0 else words.minOf { prefixCost(qw, it) }
            if (best > budget(qw.length)) return null
            total += best
        }
        return total
    }

    /** Exact matches, or if there are none the fuzzy ones closest first; ties keep the list order (favorites, then recent). */
    fun <T> filter(items: List<T>, query: String, name: (T) -> String): List<T> {
        val found = items.mapNotNull { item -> cost(query, name(item))?.let { item to it } }
        val exact = found.filter { it.second == 0.0 }
        return (exact.ifEmpty { found.sortedBy { it.second } }).map { it.first }
    }
}
