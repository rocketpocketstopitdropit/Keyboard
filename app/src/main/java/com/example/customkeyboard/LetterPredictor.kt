package com.example.customkeyboard

/**
 * Guesses the next letter, for the debug highlight. It only ever draws on
 * real words (the dictionary plus words the person added), never on letter
 * patterns, so it can't suggest a letter that leads to a non-word.
 */
object LetterPredictor {
    fun predictNext(previousWord: String, wordSoFar: String): Char? =
        WordPredictor.predictNextChar(previousWord, wordSoFar)
}
