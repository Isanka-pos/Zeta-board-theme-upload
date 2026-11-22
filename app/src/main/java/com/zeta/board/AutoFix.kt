package com.zeta.board

/**
 * Auto-fix for English typing: when a word is finished (space or punctuation) a few very common typos are replaced
 * (teh -> the, dont -> don't, im -> I'm). Backspace right after a fix puts back what was typed.
 * Pure functions, fully offline. Sinhala (Singlish) typing is not touched.
 */
object AutoFix {
    // typed (lower case) -> correct. Values that start with a capital letter (I'm) are used exactly as written.
    private val fixes: Map<String, String> = mapOf(
        "i" to "I", "im" to "I'm", "ive" to "I've",
        "teh" to "the", "hte" to "the", "adn" to "and", "nad" to "and", "taht" to "that", "waht" to "what",
        "jsut" to "just", "knwo" to "know", "wich" to "which",
        "dont" to "don't", "cant" to "can't", "wont" to "won't", "didnt" to "didn't", "doesnt" to "doesn't",
        "isnt" to "isn't", "wasnt" to "wasn't", "arent" to "aren't", "couldnt" to "couldn't", "wouldnt" to "wouldn't",
        "shouldnt" to "shouldn't", "havent" to "haven't", "hasnt" to "hasn't", "youre" to "you're", "thats" to "that's",
        "thier" to "their", "recieve" to "receive", "wierd" to "weird", "definately" to "definitely",
        "seperate" to "separate", "becuase" to "because", "becasue" to "because", "freind" to "friend",
        "tommorow" to "tomorrow", "tomorow" to "tomorrow", "alot" to "a lot", "untill" to "until",
        "occured" to "occurred", "accomodate" to "accommodate", "goverment" to "government",
        "enviroment" to "environment", "beleive" to "believe", "calender" to "calendar", "thankyou" to "thank you",
        "somthing" to "something", "diffrent" to "different", "realy" to "really", "truely" to "truly"
    )

    /** Characters that end a word. */
    fun isDelimiter(c: Char): Boolean = c == ' ' || c == '.' || c == ',' || c == '!' || c == '?' || c == ';' || c == ':'

    fun isWordChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c == '\''

    /** The fixed spelling of [word], or null when it needs no fix (or it was typed in ALL CAPS / mixed case). */
    fun fix(word: String): String? {
        if (word.isEmpty()) return null
        val lower = word.lowercase()
        val target = fixes[lower] ?: return null
        if (target == word) return null
        // "i" must stay lower case only when typed lower case; "I" alone needs nothing
        if (lower == "i") return if (word == "i") "I" else null
        val cap = word[0].isUpperCase()
        val rest = word.drop(1)
        if (rest.isNotEmpty() && rest != rest.lowercase()) return null // TEH, tEh: leave it
        if (target[0].isUpperCase()) return target // I'm, I've
        return if (cap) target[0].uppercaseChar() + target.drop(1) else target
    }
}
