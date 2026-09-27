package com.example.customkeyboard

data class KeyConfig(
    val label: String,
    val topLeft: String? = null,
    val topRight: String? = null,
    val bottomLeft: String? = null,
    val bottomRight: String? = null,
    val weight: Float = 1f,
    val action: KeyAction = KeyAction.CHAR,
    val colorHex: String? = null
)

enum class KeyAction { CHAR, SHIFT, BACKSPACE, ENTER, SPACE, SYMBOLS }

object KeyboardLayout {

    const val KEY_HEIGHT_DP = 52

    val NUMBER_ROW: List<KeyConfig> = listOf(
        KeyConfig("1"), KeyConfig("2"), KeyConfig("3"), KeyConfig("4"), KeyConfig("5"),
        KeyConfig("6"), KeyConfig("7"), KeyConfig("8"), KeyConfig("9"), KeyConfig("0")
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
