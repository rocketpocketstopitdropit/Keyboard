package com.example.customkeyboard

/**
 * Turns spoken punctuation into marks ("see you there period" -> "see you
 * there."), for recognizers that don't punctuate on their own. "Period" is
 * left as a word after "a", "the", "this"... ("a period of time").
 */
object SpokenPunctuation {

    private val MARKS: List<Pair<Regex, String>> = listOf(
        "question mark" to "?",
        "exclamation (?:point|mark)" to "!",
        "full stop" to ".",
        "period" to ".",
        "comma" to ",",
        "semicolon" to ";",
        "colon" to ":",
        "new paragraph" to "\n\n",
        "new line" to "\n"
    ).map { (spoken, mark) -> Regex("(?i)(^|\\s+)($spoken)(?=\\s|$)") to mark }

    /** After these, "period", "colon" and "comma" are ordinary words. */
    private val KEEP_AS_WORD_AFTER = setOf(
        "a", "the", "this", "that", "each", "every", "trial", "grace", "waiting", "time",
        "one", "second", "third", "first", "last", "next", "same", "my", "your", "her", "his", "their", "our"
    )

    fun apply(text: String): String {
        var out = text
        for ((regex, mark) in MARKS) {
            out = regex.replace(out) { m ->
                val before = out.substring(0, m.range.first).trimEnd()
                val lastWord = before.substringAfterLast(' ').lowercase()
                if (lastWord in KEEP_AS_WORD_AFTER) m.value else mark
            }
        }
        // Marks hug the word before them.
        out = out.replace(Regex(" +([.,!?;:])"), "$1")
        out = out.replace(Regex(" *\n *"), "\n")
        // A new sentence after . ! ? starts with a capital.
        out = Regex("([.!?]\\s+)([a-z])").replace(out) { it.groupValues[1] + it.groupValues[2].uppercase() }
        return out.trim(' ')
    }

    /** "And then" -> "and then" when the stretch of speech continues a sentence; names stay capitalized. */
    fun lowerFirstWordIfCommon(text: String): String {
        val first = text.takeWhile { it.isLetter() || it == '\'' || it == '’' }
        if (first.isEmpty() || !first[0].isUpperCase()) return text
        val lower = first.lowercase()
        if (lower == "i" || lower.startsWith("i'") || lower.startsWith("i’")) return text
        val zipf = WordPredictor.zipfOf(lower) ?: return text
        if (zipf < 4.0 || !WordPredictor.isKnown(lower)) return text
        return lower + text.substring(first.length)
    }
}
