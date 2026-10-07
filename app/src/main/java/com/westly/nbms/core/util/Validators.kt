package com.westly.nbms.core.util

object Validators {

    private val EMAIL = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    private val PHONE_CHARS = Regex("^\\+?[0-9\\s\\-()]+$")

    fun email(s: String): Boolean = EMAIL.matches(s.trim())

    /** 7 to 15 digits, optionally starting with "+", spaces, dashes and brackets allowed. */
    fun phone(s: String): Boolean {
        val t = s.trim()
        if (!PHONE_CHARS.matches(t)) return false
        val digits = t.count { it in '0'..'9' }
        return digits in 7..15
    }

    /** 4 to 6 digits, nothing else. */
    fun pin(s: String): Boolean = s.length in 4..6 && s.all { it in '0'..'9' }

    /** Returns null when valid, otherwise a message to show under the field. */
    fun password(s: String): String? {
        val ok = s.length >= 8 && s.any { it.isLetter() } && s.any { it.isDigit() }
        return if (ok) null else "Password must be at least 8 characters with a letter and a number."
    }
}
