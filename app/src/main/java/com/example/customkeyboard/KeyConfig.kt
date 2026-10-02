package com.example.customkeyboard

data class KeyConfig(
    val label: String,
    val topLeft: String? = null,
    val topRight: String? = null,
    val bottomLeft: String? = null,
    val bottomRight: String? = null,
    val weight: Float = 1f,
    val action: KeyAction = KeyAction.CHAR,
    val colorHex: String? = null,
    val shiftLabel: String? = null,
    // Per-key height override, in dp. null = use the global "Key height" setting.
    val heightDp: Int? = null,
    // What actually gets typed, if different from the visible label
    // (used for clipboard entries: short preview label, full pasted text here).
    val commitOverride: String? = null,
    // Last key of a row only: it also fills the same spot in the row below,
    // making one tall key (the backspace beside the bottom two rows).
    val spansTwoRows: Boolean = false
)

enum class KeyAction {
    CHAR, SHIFT, BACKSPACE, ENTER, SPACE, SYMBOLS, SYMBOLS_ALT, LETTERS,
    SPACER // invisible, non-tappable filler — not a real key
}

object KeyboardLayout {

    const val KEY_HEIGHT_DP = 52

    private fun plain(vararg labels: String): List<KeyConfig> = labels.map { KeyConfig(it) }
    private fun plainW(weight: Float, vararg labels: String): List<KeyConfig> =
        labels.map { KeyConfig(it, weight = weight) }

    val NOTCH_KEY = KeyConfig("⏎", weight = 1.5f, action = KeyAction.ENTER)

    private val BACKSPACE = KeyConfig("⌫", weight = 1.5f, action = KeyAction.BACKSPACE)

    /** The built-in key at this position. row = -1 is the notch key. */
    fun defaultFor(row: Int, col: Int): KeyConfig = if (row < 0) NOTCH_KEY else ROWS[row][col]

    val NUMBER_ROW: List<KeyConfig> = listOf(
        KeyConfig("1", shiftLabel = "!"), KeyConfig("2", shiftLabel = "@"),
        KeyConfig("3", shiftLabel = "#"), KeyConfig("4", shiftLabel = "\$"),
        KeyConfig("5", shiftLabel = "%"), KeyConfig("6", shiftLabel = "^"),
        KeyConfig("7", shiftLabel = "&"), KeyConfig("8", shiftLabel = "*"),
        KeyConfig("9", shiftLabel = "("), KeyConfig("0", shiftLabel = ")")
    )

    val SYMBOLS_1: List<List<KeyConfig>> = listOf(
        plain("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        plain("-", "/", ":", ";", "(", ")", "\$", "&", "@", "\""),
        listOf(KeyConfig("=\\<", weight = 2f, action = KeyAction.SYMBOLS_ALT)) +
                plainW(1.6f, ".", ",", "?", "!", "'"),
        listOf(
            KeyConfig("ABC", weight = 1.5f, action = KeyAction.LETTERS),
            KeyConfig(" ", weight = 5f, action = KeyAction.SPACE),
            BACKSPACE
        )
    )

    val SYMBOLS_2: List<List<KeyConfig>> = listOf(
        plain("[", "]", "{", "}", "#", "%", "^", "*", "+", "="),
        plain("_", "\\", "|", "~", "<", ">", "€", "£", "¥", "•"),
        listOf(KeyConfig("123", weight = 2f, action = KeyAction.SYMBOLS)) +
                plainW(1.6f, ".", ",", "?", "!", "'"),
        listOf(
            KeyConfig("ABC", weight = 1.5f, action = KeyAction.LETTERS),
            KeyConfig(" ", weight = 5f, action = KeyAction.SPACE),
            BACKSPACE
        )
    )

    /** Bottom row of the emoji and clipboard pages: back to letters on the left, backspace on the right. */
    val PICKER_BOTTOM_ROW: List<KeyConfig> = listOf(
        KeyConfig("ABC", weight = 2f, action = KeyAction.LETTERS),
        KeyConfig("", weight = 4f, action = KeyAction.SPACER),
        BACKSPACE.copy(weight = 2f)
    )

    // Emoji picker page — flat grid, 8 per row.
    val EMOJI_ROWS: List<List<KeyConfig>> = listOf(
        plain("😀", "😁", "😂", "🤣", "😊", "😉", "😍", "🥰"),
        plain("😘", "😜", "🤔", "🙄", "😏", "😴", "🥱", "😮"),
        plain("😢", "😭", "😡", "🤬", "😱", "🥳", "🤯", "🤗"),
        plain("😎", "🤓", "😇", "🙃", "😬", "🤐", "🤒", "🤕"),
        plain("👍", "👎", "👏", "🙏", "💪", "🤝", "✌️", "🤞"),
        plain("👋", "🤙", "👌", "🤌", "👉", "👈", "☝️", "✋"),
        plain("❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍"),
        plain("🔥", "🎉", "✅", "❌", "⭐", "💯", "🙌", "👀"),
        plain("💀", "👻", "🤖", "👽", "💩", "🎃", "😺", "🐶"),
        plain("🐱", "🐭", "🐰", "🦊", "🐻", "🐼", "🐨", "🐷"),
        plain("☕", "🍕", "🍔", "🍟", "🍎", "🍺", "🍾", "🎂"),
        plain("⚽", "🏀", "🏈", "🎮", "🎧", "🚗", "✈️", "🏠"),
        plain("🌙", "☀️", "⛅", "🌧️", "❄️", "🌈", "⚡", "🎄"),
        PICKER_BOTTOM_ROW
    )

    val ROWS: List<List<KeyConfig>> = listOf(
        listOf(
            KeyConfig("q"), KeyConfig("w"), KeyConfig("e"), KeyConfig("r"),
            KeyConfig("t"), KeyConfig("y"), KeyConfig("u"), KeyConfig("i"),
            KeyConfig("o"), KeyConfig("p")
        ),
        listOf(
            KeyConfig(
                "a",
                topLeft = "😀", topRight = "😂",
                bottomLeft = "😍", bottomRight = "👍"
            ),
            KeyConfig("s"), KeyConfig("d"), KeyConfig("f"), KeyConfig("g"),
            KeyConfig("h"), KeyConfig("j"), KeyConfig("k"), KeyConfig("l")
        ),
        listOf(
            KeyConfig("⇧", weight = 1.5f, action = KeyAction.SHIFT),
            KeyConfig("z", weight = 1.2f), KeyConfig("x", weight = 1.2f),
            KeyConfig("c", weight = 1.2f), KeyConfig("v", weight = 1.2f),
            KeyConfig("b", weight = 1.2f), KeyConfig("n", weight = 1.2f),
            KeyConfig("m", weight = 1.2f),
            // Backspace where it traditionally sits, running down past the
            // bottom row into the corner (enter lives in the notch up top).
            BACKSPACE.copy(spansTwoRows = true)
        ),
        listOf(
            KeyConfig("123", weight = 1.5f, action = KeyAction.SYMBOLS),
            KeyConfig(",", topLeft = "!", topRight = "?"),
            KeyConfig(" ", weight = 4f, action = KeyAction.SPACE),
            KeyConfig(".", topLeft = ";", topRight = ":")
        )
    )
}
