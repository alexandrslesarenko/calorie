package com.calorie.app.logic

import java.security.MessageDigest

/**
 * Keys for the dish library and the Claude answer cache. No Android dependencies, so the
 * normalization can be unit tested.
 */
object Keys {
    private val SPACES = Regex("\\s+")

    /** One library dish per name: case and extra spaces do not matter. */
    fun dish(name: String): String = name.trim().lowercase().replace(SPACES, " ")

    /**
     * Key of a photo answer: the prepared JPEG bytes, the hint and the answer language.
     * The same gallery picture with the same hint gets the same answer, without a request.
     */
    fun photo(jpeg: ByteArray, hint: String?, language: String): String =
        sha256("photo", language, text(hint ?: "")) { it.update(jpeg) }

    /** Key of a text description answer: "Borscht  and bread" and "borscht and bread" are the same. */
    fun description(description: String, language: String): String = sha256("text", language, text(description))

    private fun text(s: String) = s.trim().lowercase().replace(SPACES, " ")

    private fun sha256(vararg parts: String, extra: (MessageDigest) -> Unit = {}): String {
        val md = MessageDigest.getInstance("SHA-256")
        // Zero byte separator: "ab"+"c" and "a"+"bc" must not collide.
        parts.forEach { md.update(it.toByteArray(Charsets.UTF_8)); md.update(0) }
        extra(md)
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
