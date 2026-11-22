package com.zeta.board

/**
 * Singlish -> Sinhala. Rule based and fully offline: letters typed in English are turned into Sinhala as you type
 * (mama gedara yanawa -> මම ගෙදර යනවා). Pure functions, no Android types, so it is easy to test.
 *
 * Rules in short:
 *  - a consonant followed by a vowel takes that vowel sign (ka -> ක, kaa -> කා, ki -> කි, ke -> කෙ)
 *  - a consonant followed by a consonant (or nothing yet) gets the hal sign (k -> ක්, amma -> අම්මා)
 *  - consonant + r / y makes rakaransaya / yansaya (kriyaa -> ක්‍රියා, vidyaa -> විද්‍යා)
 *  - capital letters give the "other" letter: T ඨ, D ඩ, N ණ, L ළ, S ෂ, K ඛ, G ඝ, C ඡ, J ඣ, P ඵ, B භ
 *  - x gives ං and H gives ඃ
 *  - a word that ends in "nawa" is written with a long final vowel (yanawa -> යනවා)
 */
object Singlish {
    private const val HAL = "\u0DCA"
    private const val ZWJ = "\u200D"

    private const val C = 0 // consonant
    private const val V = 1 // vowel
    private const val M = 2 // ං / ඃ
    private const val O = 3 // anything else, passed through

    private class Tok(val kind: Int, val a: String, val b: String = "")

    /** Roman letters -> Sinhala consonant. Keys are case sensitive; a key that is not found is tried again in lower case. */
    private val cons: Map<String, String> = mapOf(
        "k" to "\u0D9A", "kh" to "\u0D9B", "K" to "\u0D9B",
        "g" to "\u0D9C", "gh" to "\u0D9D", "G" to "\u0D9D", "ng" to "\u0D9F",
        "c" to "\u0DA0", "ch" to "\u0DA0", "chh" to "\u0DA1", "C" to "\u0DA1",
        "j" to "\u0DA2", "jh" to "\u0DA3", "J" to "\u0DA3",
        "jn" to "\u0DA4", "gn" to "\u0DA5",
        "t" to "\u0DA7", "T" to "\u0DA8",
        "d" to "\u0DAF", "dh" to "\u0DB0", "D" to "\u0DA9", "Dh" to "\u0DAA",
        "th" to "\u0DAD", "Th" to "\u0DAE",
        "n" to "\u0DB1", "N" to "\u0DAB",
        "p" to "\u0DB4", "ph" to "\u0DB5", "P" to "\u0DB5",
        "b" to "\u0DB6", "bh" to "\u0DB7", "B" to "\u0DB7",
        "m" to "\u0DB8", "mb" to "\u0DB9",
        "y" to "\u0DBA", "r" to "\u0DBB",
        "l" to "\u0DBD", "L" to "\u0DC5",
        "w" to "\u0DC0", "v" to "\u0DC0",
        "sh" to "\u0DC1", "S" to "\u0DC2", "Sh" to "\u0DC2",
        "s" to "\u0DC3", "h" to "\u0DC4", "f" to "\u0DC6"
    )

    /** Roman vowel -> (letter when it starts a syllable, sign when it follows a consonant). */
    private val vowels: Map<String, Pair<String, String>> = mapOf(
        "a" to Pair("\u0D85", ""),
        "aa" to Pair("\u0D86", "\u0DCF"),
        "ae" to Pair("\u0D87", "\u0DD0"),
        "aae" to Pair("\u0D88", "\u0DD1"),
        "i" to Pair("\u0D89", "\u0DD2"),
        "ii" to Pair("\u0D8A", "\u0DD3"),
        "u" to Pair("\u0D8B", "\u0DD4"),
        "uu" to Pair("\u0D8C", "\u0DD6"),
        "e" to Pair("\u0D91", "\u0DD9"),
        "ee" to Pair("\u0D92", "\u0DDA"),
        "ai" to Pair("\u0D93", "\u0DDB"),
        "o" to Pair("\u0D94", "\u0DDC"),
        "oo" to Pair("\u0D95", "\u0DDD"),
        "au" to Pair("\u0D96", "\u0DDE")
    )

    private val marks: Map<String, String> = mapOf("x" to "\u0D82", "H" to "\u0D83")

    private fun lookup(key: String): Tok? {
        cons[key]?.let { return Tok(C, it) }
        vowels[key]?.let { return Tok(V, it.first, it.second) }
        marks[key]?.let { return Tok(M, it) }
        return null
    }

    /** Finds the longest rule at [i]: returns the token and how many typed letters it used. */
    private fun next(s: String, i: Int): Pair<Tok, Int> {
        var len = minOf(3, s.length - i)
        while (len >= 1) {
            val sub = s.substring(i, i + len)
            lookup(sub)?.let { return Pair(it, len) }
            val lo = sub.lowercase()
            if (lo != sub) lookup(lo)?.let { return Pair(it, len) }
            len--
        }
        return Pair(Tok(O, s.substring(i, i + 1)), 1)
    }

    /** True for the letters that Singlish typing takes over (A-Z, a-z). */
    fun isLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

    /** Turns the roman letters typed so far into Sinhala. Safe to call on every key press. */
    fun convert(roman: String): String {
        if (roman.isEmpty()) return ""
        val src = if (roman.endsWith("nawa")) roman + "a" else roman
        val toks = ArrayList<Tok>()
        var i = 0
        while (i < src.length) {
            val (t, n) = next(src, i)
            toks.add(t)
            i += n
        }
        val sb = StringBuilder()
        var halOpen = false // the text so far ends with a consonant + hal sign (no vowel yet)
        var k = 0
        while (k < toks.size) {
            val t = toks[k]
            if (t.kind == C) {
                // a consonant with no vowel in front of ර / ය joins it as rakaransaya / yansaya
                if (halOpen && (t.a == "\u0DBB" || t.a == "\u0DBA")) sb.append(ZWJ)
                sb.append(t.a)
                val nx = if (k + 1 < toks.size) toks[k + 1] else null
                if (nx != null && nx.kind == V) {
                    sb.append(nx.b)
                    k++
                    halOpen = false
                } else if (nx != null && nx.kind == M) {
                    halOpen = false
                } else {
                    sb.append(HAL)
                    halOpen = true
                }
            } else {
                sb.append(t.a)
                halOpen = false
            }
            k++
        }
        return sb.toString()
    }

    /**
     * The Sinhala letter that the key [c] gives (shown as a small hint on the key). [upper] = Shift is on.
     * Null for keys that give no Sinhala (q, z, digits, punctuation).
     */
    fun hint(c: Char, upper: Boolean): String? {
        if (!isLetter(c)) return null
        val ch = if (upper) c.uppercaseChar() else c.lowercaseChar()
        var r = convert(ch.toString())
        if (r.endsWith(HAL)) r = r.dropLast(1)
        if (r.isEmpty() || r[0] < '\u0D80') return null
        return r
    }
}
