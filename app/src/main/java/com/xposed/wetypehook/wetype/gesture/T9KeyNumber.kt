package com.xposed.wetypehook.wetype.gesture

/** IDs contain the keyboard number too (S1); never use its first numeric character. */
internal fun resolveT9KeyNumber(text: String): Char? {
    val value = text.trim()
    val id = Regex("(?i)^(?:S[0-9]+_key_|(?:t9|nine|key)[_-])([1-9])$")
        .matchEntire(value)?.groupValues?.get(1)
    if (id != null) return id.single()
    return value.singleOrNull()?.takeIf { it in '1'..'9' }
}
