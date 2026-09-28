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
    // What this key shows and types while Shift is on.
    // null = uppercase of the label for letters, unchanged for anything else.
    val shiftLabel: String? = null
)

enum class KeyAction { CHAR, SHIFT, BACKSPACE, ENTER, SPACE, SYMBOLS, SYMBOLS_ALT, LETTERS }

object KeyboardLayout {

    const val KEY_HEIGHT_DP = 52

    private fun plain(vararg labels: String): List<KeyConfig> = labels.map { KeyConfig(it) }

    // Optional row above the letters. Shows these symbols while Shift is on.
    val NUMBER_ROW: List<KeyConfig> = listOf(
        KeyConfig("1", shiftLabel = "!"), KeyConfig("2", shiftLabel = "@"),
        KeyConfig("3", shiftLabel = "#"), KeyConfig("4", shiftLabel = "\$"),
        KeyConfig("5", shiftLabel = "%"), KeyConfig("6", shiftLabel = "^"),
        KeyConfig("7", shiftLabel = "&"), KeyConfig("8", shiftLabel = "*"),
        KeyConfig("9", shiftLabel = "("), KeyConfig("0", shiftLabel = ")")
    )

    // Numbers and symbols page, reached with the 123 key.
    val SYMBOLS_1: List<List<KeyConfig>> = listOf(
        plain("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        plain("-", "/", ":", ";", "(", ")", "\$", "&", "@", "\""),
        listOf(KeyConfig("=\\<", weight = 1.5f, action = KeyAction.SYMBOLS_ALT)) +
                plain(".", ",", "?", "!", "'") +
                listOf(KeyConfig("⌫", weight = 1.5f, action = KeyAction.BACKSPACE)),
        listOf(
            KeyConfig("ABC", weight = 1.5f, action = KeyAction.LETTERS),
            KeyConfig(" ", weight = 5f, action = KeyAction.SPACE),
            KeyConfig("⏎", weight = 1.5f, action = KeyAction.ENTER)
        )
    )

    // Second symbols page, reached with the =\< key.
    val SYMBOLS_2: List<List<KeyConfig>> = listOf(
        plain("[", "]", "{", "}", "#", "%", "^", "*", "+", "="),
        plain("_", "\\", "|", "~", "<", ">", "€", "£", "¥", "•"),
        listOf(KeyConfig("123", weight = 1.5f, action = KeyAction.SYMBOLS)) +
                plain(".", ",", "?", "!", "'") +
                listOf(KeyConfig("⌫", weight = 1.5f, action = KeyAction.BACKSPACE)),
        listOf(
            KeyConfig("ABC", weight = 1.5f, action = KeyAction.LETTERS),
            KeyConfig(" ", weight = 5f, action = KeyAction.SPACE),
            KeyConfig("⏎", weight = 1.5f, action = KeyAction.ENTER)
        )
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
            KeyConfig("z"), KeyConfig("x"), KeyConfig("c"), KeyConfig("v"),
            KeyConfig("b"), KeyConfig("n"), KeyConfig("m"),
            KeyConfig("⌫", weight = 1.5f, action = KeyAction.BACKSPACE)
        ),
        listOf(
            KeyConfig("123", weight = 1.5f, action = KeyAction.SYMBOLS),
            KeyConfig(",", topLeft = "!", topRight = "?"),
            KeyConfig(" ", weight = 4f, action = KeyAction.SPACE),
            KeyConfig(".", topLeft = ";", topRight = ":"),
            KeyConfig("⏎", weight = 1.5f, action = KeyAction.ENTER)
        )
    )
}
