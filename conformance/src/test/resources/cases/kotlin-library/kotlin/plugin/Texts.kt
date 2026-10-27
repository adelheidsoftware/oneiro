package plugin

/** Kotlin's functions on text: replace, lines, its nullable forms, and tests of its chars. */
object Texts {

	/** The name with its spaces as underscores. */
	fun underscored(name: String): String = name.replace(" ", "_")

	/** How many lines the text has. */
	fun lineCount(text: String): Int = text.lines().size

	/** The name, or empty text for none. */
	fun orNothing(name: String?): String = name.orEmpty()

	/** Whether there is no name. */
	fun unnamed(name: String?): Boolean = name.isNullOrEmpty()

	/** Whether there is no name but spaces. */
	fun blank(name: String?): Boolean = name.isNullOrBlank()

	/** How many times the letter is in the word. */
	fun letters(word: String, letter: Char): Int = word.count { it == letter }

	/** What the text is, by whether it has a digit. */
	fun kind(text: String): String {
		if (text.any { it.isDigit() }) {
			return "code"
		}
		return "word"
	}

	/** Whether every char of the text is a letter. */
	fun lettersOnly(text: String): Boolean {
		val letters = text.all { it.isLetter() }
		return letters
	}
}
