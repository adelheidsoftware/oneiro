package plugin

/** Letters and digits, one char at a time. */
object Letters {

	/** The text with each lowercase letter moved along the alphabet, wrapping from z to a. */
	fun caesar(text: String, shift: Int): String {
		val shifted = StringBuilder()
		for (c in text) {
			if (c in 'a'..'z') {
				val index = (c - 'a' + shift) % 26
				shifted.append('a' + index)
			} else {
				shifted.append(c)
			}
		}
		return shifted.toString()
	}

	/** The capital letters of a name, such as "SC" for "Session Clock". */
	fun initials(name: String): String {
		var initials = ""
		for (i in name.indices) {
			val c = name[i]
			if (c.isUpperCase()) {
				initials += c
			}
		}
		return initials
	}

	/** The sum of the digits in the text, skipping everything else. */
	fun digitSum(text: String): Int {
		var sum = 0
		for (c in text) {
			if (c.isDigit()) {
				sum += c - '0'
			}
		}
		return sum
	}

	/** How often each letter comes up, ignoring case. */
	fun letterCounts(text: String): Map<Char, Int> {
		val counts = HashMap<Char, Int>()
		for (c in text.lowercase()) {
			if (c.isLetter()) {
				counts[c] = (counts[c] ?: 0) + 1
			}
		}
		return counts
	}

	/** The letters from one to another, such as "abc" from 'a' to 'c'. */
	fun alphabet(from: Char, to: Char): String {
		val letters = StringBuilder()
		var c = from
		while (c <= to) {
			letters.append(c)
			c++
		}
		return letters.toString()
	}

	/** The number Kotlin gives the char. */
	fun code(c: Char): Int {
		return c.code
	}

	/** The char with the number. */
	fun fromCode(code: Int): Char {
		return code.toChar()
	}

	/** The grade for a tier from 1 to 3. */
	fun grade(tier: Int): Char {
		return when (tier) {
			1 -> 'A'
			2 -> 'B'
			else -> 'F'
		}
	}

	/** The char with its number, such as "A=65". */
	fun describe(c: Char): String {
		return "%c=%d".format(c, c.code)
	}

	/** The word with its first letter capitalised. */
	fun capitalised(word: String): String {
		return word[0].uppercase() + word.substring(1)
	}

	/** Where the char is first found in the text, or -1. */
	fun find(text: String, sought: Char): Int {
		return text.indexOf(sought)
	}

	/** The chars as text, a blank, and how many there are. */
	fun joined(chars: CharArray): String {
		val blanks = CharArray(1)
		blanks[0] = ' '
		return String(chars) + String(blanks) + chars.size
	}
}
