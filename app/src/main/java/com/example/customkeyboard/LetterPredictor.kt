package com.example.customkeyboard

object LetterPredictor {

    private val trigramMap: Map<String, Char> = mapOf(
        "th" to 'e', "he" to 'r', "in" to 'g', "er" to 's', "an" to 'd',
        "re" to ' ', "on" to ' ', "at" to 'i', "en" to 't', "nd" to ' ',
        "ti" to 'o', "es" to ' ', "or" to ' ', "te" to 'r', "of" to ' ',
        "ed" to ' ', "is" to ' ', "it" to ' ', "al" to 'l', "ar" to ' ',
        "st" to ' ', "to" to ' ', "nt" to ' ', "ng" to ' ', "se" to ' ',
        "ha" to 't', "as" to ' ', "ou" to 'r', "io" to 'n', "le" to ' ',
        "ve" to ' ', "co" to 'n', "me" to ' ', "de" to ' ', "hi" to 's',
        "ri" to 'n', "ro" to ' ', "ic" to ' ', "ne" to ' ', "ea" to 'r',
        "ra" to 't', "ce" to ' ', "li" to 'n', "ch" to ' ', "ll" to ' ',
        "be" to ' ', "ma" to 'n', "si" to 'n', "om" to ' ', "ur" to ' '
    )

    private val bigramMap: Map<Char, Char> = mapOf(
        'a' to 'n', 'b' to 'e', 'c' to 'o', 'd' to 'e', 'e' to 'r',
        's' to 't', 't' to 'h', 'i' to 'n', 'n' to 'g', 'o' to 'n',
        'r' to 'e', 'l' to 'e', 'u' to 'r', 'h' to 'e', 'm' to 'e',
        'w' to 'a', 'y' to ' ', 'p' to 'r', 'g' to ' ', 'f' to 'o',
        'q' to 'u', 'x' to 't', 'z' to 'e', 'j' to 'u', 'k' to ' ',
        'v' to 'e'
    )

    fun predictNext(previousWord: String, wordSoFar: String): Char? {
        val lower = wordSoFar.lowercase()

        WordPredictor.predictNextChar(previousWord, lower)?.let { return it }

        if (lower.isEmpty()) return null

        if (lower.length >= 2) {
            trigramMap[lower.takeLast(2)]?.let { return it }
        }
        return bigramMap[lower.last()]
    }
}
