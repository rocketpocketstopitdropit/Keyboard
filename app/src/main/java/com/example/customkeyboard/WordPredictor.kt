package com.example.customkeyboard

object WordPredictor {

    val COMMON_WORDS: List<String> = listOf(
        "the", "and", "that", "have", "for", "not", "with", "you", "this", "but",
        "his", "from", "they", "say", "her", "she", "will", "one", "all", "would",
        "there", "their", "what", "out", "about", "who", "get", "which", "when", "make",
        "can", "like", "time", "just", "him", "know", "take", "people", "into", "year",
        "your", "good", "some", "could", "them", "see", "other", "than", "then", "now",
        "look", "only", "come", "its", "over", "think", "also", "back", "after", "use",
        "two", "how", "our", "work", "first", "well", "way", "even", "new", "want",
        "because", "any", "these", "give", "day", "most", "us", "is", "are", "was",
        "were", "been", "being", "has", "had", "do", "does", "did", "should", "would",
        "might", "must", "may", "shall", "cannot", "hello", "thanks", "thank", "please",
        "sorry", "okay", "alright", "maybe", "actually", "probably", "definitely",
        "awesome", "great", "love", "happy", "today", "tomorrow", "yesterday",
        "morning", "night", "weekend", "family", "friend", "home", "phone", "message",
        "school", "house", "world", "something", "someone", "really", "little",
        "before", "right", "through", "picture", "music", "movie", "dinner", "lunch",
        "breakfast", "coffee", "water", "weather", "exercise", "project", "meeting",
        "schedule", "question", "answer", "problem", "reason", "important", "different",
        "interesting", "beautiful", "wonderful", "remember", "forget", "understand",
        "explain", "imagine", "suggest", "decide", "believe", "change"
    )

    fun predictNextChar(prefix: String): Char? {
        if (prefix.isEmpty()) return null
        val lower = prefix.lowercase()
        val match = COMMON_WORDS.firstOrNull { it.length > lower.length && it.startsWith(lower) }
        return match?.get(lower.length)
    }
}
