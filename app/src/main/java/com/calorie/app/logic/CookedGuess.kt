package com.calorie.app.logic

import org.json.JSONObject

/**
 * Food that changes weight in cooking, with what 100 g as sold usually weigh cooked.
 * key is stored in the user's own numbers - do not rename.
 */
enum class CookKind(val key: String, val cooked: Double, val dry: Boolean = true) {
    PASTA("pasta", 230.0),
    COUSCOUS("couscous", 250.0),
    MILLET("millet", 300.0),
    RICE("rice", 280.0),
    BUCKWHEAT("buckwheat", 250.0),
    BARLEY("barley", 300.0),
    BULGUR("bulgur", 260.0),
    QUINOA("quinoa", 270.0),
    LENTILS("lentils", 240.0),
    CHICKPEAS("chickpeas", 220.0),
    BEANS("beans", 240.0),
    PEAS("peas", 240.0),
    MEAT("meat", 70.0, dry = false),
    FISH("fish", 80.0, dry = false);

    companion object {
        fun byKey(k: String?) = entries.firstOrNull { it.key == k }
    }
}

/** A number to put into "100 g weigh cooked"; own - the user's earlier number, not the table. */
data class CookedValue(val grams: Double, val own: Boolean)

/**
 * Guesses what 100 g as sold weigh cooked, so the field is not empty: the user's own number
 * for this dish, then for this kind of food, then the table. Only a suggestion - it is
 * shown in the field and can be changed.
 *
 * The kind comes from the Open Food Facts category when there is one (it does not depend on
 * the language of the name), otherwise from words in the name. Dry kinds need at least
 * [Cooking.DRY_KCAL] per 100 g: cooked rice, canned beans and ready pasta are lower and must
 * not be converted again. Meat and fish words count only at the start of the name:
 * "dumplings with meat" is not meat.
 */
object CookedGuess {
    /** The user's numbers per dish: enough for the foods cooked often, the rest goes. */
    const val MAX_DISHES = 100

    private const val DISH = "d:"
    private const val KIND = "k:"

    fun guess(name: String, kcalAsSold: Double, offKind: CookKind?, mine: Map<String, Double>): CookedValue? {
        mine[DISH + Keys.dish(name)]?.let { return CookedValue(it, true) }
        val kind = (offKind ?: kindOf(name))?.takeIf { !it.dry || kcalAsSold >= Cooking.DRY_KCAL } ?: return null
        mine[KIND + kind.key]?.let { return CookedValue(it, true) }
        return CookedValue(kind.cooked, false)
    }

    /**
     * The user's numbers after saving [cooked] for [name]. Kept for the dish always, for the
     * kind only when it differs from the table: then it is the user's pot and their way of cooking.
     * Newest last, so the oldest dishes drop out first.
     */
    fun remember(mine: Map<String, Double>, name: String, kcalAsSold: Double, offKind: CookKind?, cooked: Double): Map<String, Double> {
        if (!Cooking.valid(cooked)) return mine
        val out = LinkedHashMap(mine)
        val dish = DISH + Keys.dish(name)
        out.remove(dish)
        out[dish] = cooked
        val kind = (offKind ?: kindOf(name))?.takeIf { !it.dry || kcalAsSold >= Cooking.DRY_KCAL }
        if (kind != null) {
            if (cooked != kind.cooked) out[KIND + kind.key] = cooked else out.remove(KIND + kind.key)
        }
        val dishes = out.keys.filter { it.startsWith(DISH) }
        dishes.take((dishes.size - MAX_DISHES).coerceAtLeast(0)).forEach { out.remove(it) }
        return out
    }

    fun encode(mine: Map<String, Double>): String = JSONObject(mine as Map<*, *>).toString()

    fun decode(s: String?): Map<String, Double> {
        if (s.isNullOrBlank()) return emptyMap()
        return try {
            val o = JSONObject(s)
            val out = LinkedHashMap<String, Double>()
            o.keys().forEach { k -> o.optDouble(k).takeIf { !it.isNaN() }?.let { out[k] = it } }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Kind by words of the name; null - nothing known, or the name says it is cooked already. */
    fun kindOf(name: String): CookKind? {
        val n = name.lowercase()
        if (COOKED.containsMatchIn(n)) return null
        WORDS.forEach { (kind, re) -> if (re.containsMatchIn(n)) return kind }
        START.forEach { (kind, re) -> if (re.containsMatchIn(n)) return kind }
        return null
    }

    /**
     * Kind by Open Food Facts category tags. Their taxonomy keeps cooked, canned and ready
     * products under the same parents (en:cooked-lentils under en:lentils), so those are skipped.
     */
    fun kindOfCategories(tags: List<String>): CookKind? {
        if (tags.any { t -> SKIP_TAGS.any { t.contains(it) } }) return null
        return TAGS.firstOrNull { (_, set) -> tags.any { it in set } }?.first
    }

    private val SKIP_TAGS = listOf(
        "cooked", "canned", "ready", "prepared", "instant", "fresh-pasta", "frozen-pasta", "smoked",
        "sausage", "ham", "breaded", "fried", "roasted", "dried-meat", "soup", "salad", "meals",
    )

    // Order matters: more specific first ("buckwheat noodles" are noodles).
    private val TAGS = listOf(
        CookKind.PASTA to setOf("en:pastas", "en:dry-pastas", "en:noodles"),
        CookKind.COUSCOUS to setOf("en:durum-wheat-semolinas-for-couscous"),
        CookKind.RICE to setOf("en:rices"),
        CookKind.BUCKWHEAT to setOf("en:buckwheat", "en:buckwheat-groats"),
        CookKind.MILLET to setOf("en:millet"),
        CookKind.BARLEY to setOf("en:barley"),
        CookKind.BULGUR to setOf("en:bulgur"),
        CookKind.QUINOA to setOf("en:quinoa"),
        CookKind.LENTILS to setOf("en:lentils", "en:dried-lentils"),
        CookKind.CHICKPEAS to setOf("en:chickpeas"),
        CookKind.PEAS to setOf("en:dried-peas"),
        CookKind.FISH to setOf("en:fishes"),
        CookKind.MEAT to setOf("en:meats", "en:poultries", "en:chickens"),
    )

    /** Not at a letter: "рис" is rice, "ирис" is a candy. CJK has no spaces, so it just matches. */
    private fun words(vararg w: String) = Regex("(?<!\\p{L})(?:" + w.joinToString("|") + ")")
    private fun start(vararg w: String) = Regex("^\\s*(?:" + w.joinToString("|") + ")")

    private val COOKED = Regex(
        "(?<!\\p{L})(?:варен|варён|отварн|готов|жарен|тушен|тушён|запеч|cooked|boiled|fried|baked|steamed|roasted|" +
            "gekocht|gebraten|cuit|cocid|hervid|cott[oaie]|lesso)|炊|茹で|ご飯|米饭|熟|煮|밥|삶은"
    )

    private val WORDS = listOf(
        CookKind.PASTA to words(
            "макарон", "спагетт", "лапш", "вермишел", "рожки", "перья", "фузилл", "пенне", "фарфалл", "тальятел",
            "феттучин", "лингвин", "pasta(?!\\p{L})", "spaghett", "macaroni", "noodle", "penne", "fusilli", "farfalle",
            "tagliatell", "fettuccin", "linguin", "vermicell", "nudel", "teigwaren", "pâtes", "nouilles", "coquillettes",
            "espagueti", "fideo", "macarrones", "maccheroni", "パスタ", "スパゲ", "マカロニ", "うどん",
            "파스타", "스파게티", "국수", "意大利面", "意面", "面条", "通心粉",
        ),
        CookKind.COUSCOUS to words("кускус", "couscous", "cuscús", "cuscus", "クスクス", "쿠스쿠스", "古斯米"),
        CookKind.MILLET to words("пшен", "просо", "millet", "hirse", "mijo", "miglio", "キビ", "기장", "小米"),
        CookKind.RICE to words("рис", "rice", "reis", "riz(?!\\p{L})", "arroz", "riso(?!\\p{L})", "白米", "玄米", "ライス", "쌀", "현미", "大米", "糙米"),
        CookKind.BUCKWHEAT to words("гречк", "гречнев", "ядриц", "buckwheat", "buchweizen", "sarrasin", "alforfón", "grano saraceno", "메밀", "荞麦"),
        CookKind.BARLEY to words("перлов", "ячнев", "ячмен", "barley", "graupen", "gerste", "orge(?!\\p{L})", "cebada", "大麦", "보리"),
        CookKind.BULGUR to words("булгур", "bulgur", "bulghur", "boulgour", "ブルグル", "불구르"),
        CookKind.QUINOA to words("киноа", "quinoa", "quinua", "キヌア", "퀴노아", "藜麦"),
        CookKind.LENTILS to words("чечевиц", "lentil", "linse", "lentille", "lenteja", "lenticch", "レンズ豆", "렌틸", "扁豆"),
        CookKind.CHICKPEAS to words("нут(?:а|ом|у)?(?!\\p{L})", "chickpea", "garbanzo", "kichererbse", "pois chiche", "ceci(?!\\p{L})", "ひよこ豆", "병아리콩", "鹰嘴豆"),
        CookKind.BEANS to words("фасол", "bean", "bohne", "haricot", "frijol", "alubia", "judía", "fagiol", "インゲン豆", "강낭콩", "芸豆"),
        CookKind.PEAS to words("горох", "split pea", "erbse", "pois cassé", "guisante", "pisell"),
    )

    private val START = listOf(
        CookKind.FISH to start(
            "рыб", "филе (?:трески|минтая|хека|лосося|семги|сёмги|горбуши)", "треск", "минтай", "хек", "лосос", "семг", "сёмг",
            "горбуш", "fish", "cod(?!\\p{L})", "salmon", "fisch", "lachs", "poisson", "saumon", "pescado", "salmón",
            "pesce", "salmone", "魚", "鮭", "생선", "연어", "鱼",
        ),
        CookKind.MEAT to start(
            "мяс", "говяд", "свинин", "баранин", "телятин", "фарш", "курин", "куриц", "цыпл", "индейк", "индюш", "утк",
            "филе", "грудк", "бедр", "окорок", "стейк", "meat", "beef", "pork", "lamb", "veal", "mince", "chicken",
            "turkey", "duck", "steak", "fleisch", "rind", "schwein", "hähnchen", "huhn", "pute", "viande", "bœuf", "boeuf",
            "porc", "poulet", "dinde", "carne", "ternera", "cerdo", "pollo", "pavo", "manzo", "maiale", "tacchino",
            "牛肉", "豚肉", "鶏肉", "鶏", "고기", "닭", "소고기", "돼지", "鸡", "猪肉", "羊肉",
        ),
    )
}
