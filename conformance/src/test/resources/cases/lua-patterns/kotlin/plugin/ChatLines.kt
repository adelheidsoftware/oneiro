package plugin

import org.adelheid.oneiro.api.LuaString

/** Chat lines read with Lua patterns, through LuaString where Kotlin would use Regex. */
object ChatLines {

	/** The fish a catch message names, such as "shark" in "You catch a shark.", or null for another line. */
	fun caught(line: String): String? {
		return LuaString.match(line, "^You catch an? (.+)%.$")
	}

	/** The amount and the item of a drop message, such as "3" and "rune bars" in "Drop: 3 x rune bars". */
	fun drop(line: String): List<String> {
		return LuaString.captures(line, "^Drop: (%d+) x (.+)$")
	}

	/** Where the line's first number starts, or -1. */
	fun firstNumber(line: String): Int {
		return LuaString.find(line, "%d")
	}

	/** The line with each number hidden, such as "Level #" for "Level 99". */
	fun masked(line: String): String {
		return LuaString.gsub(line, "%d+", "#")
	}

	/** The words of the line. */
	fun words(line: String): List<String> {
		val words = ArrayList<String>()
		for (word in LuaString.gmatch(line, "%a+")) {
			words.add(word)
		}
		return words
	}
}
