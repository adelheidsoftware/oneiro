package plugin

/** Strings in the house style: formatStr for building, table.concat for joining. */
object Strings {

	fun greeting(name: String, level: Int): String {
		return "Hello $name, level $level"
	}

	fun joined(first: String, second: String): String {
		return first + second
	}

	fun tracking(id: Long, ratio: Double, done: Boolean): String {
		return "id $id ratio $ratio done $done"
	}

	fun formatted(count: Int, share: Double, big: Long, mask: Int): String {
		return "%5d|%-6s|%.2f|%x|%08X|%+d|%%".format(count, "ab", share, mask, mask, big)
	}

	fun listed(names: List<String>): String {
		val text = StringBuilder("names:")
		for (name in names) {
			text.append(" ").append(name)
		}
		return text.toString()
	}

	fun methods(text: String): String {
		if (text.isEmpty() || !text.startsWith("x")) {
			return text.uppercase()
		}
		return text.substring(1, 3) + text.trim() + text.indexOf("y") + text.length
	}

	fun literal(): String {
		return "a{b}" + "c"
	}

	fun braced(value: Int): String {
		return "{$value}"
	}

	fun counted(count: Int): String {
		var text = "count"
		text += "=$count"
		return text
	}

	fun compare(a: String, b: String): Boolean {
		return a == b || a.equals(b, ignoreCase = true) || a.contains(b) || a.endsWith(".lua") || a.endsWith(b)
	}

	fun converted(count: Int, ratio: Double, id: Long, flag: Boolean): String {
		val number = count.toString() + count.toString()
		return number + ratio.toString() + flag.toString() + id.toString()
	}

	fun changed(text: String): String {
		return text.repeat(2) + text.lowercase()
	}

	fun parsed(text: String): Int {
		return text.toInt() + "7".toInt()
	}

	fun joinedAll(names: List<String>): String {
		return names.joinToString(", ") + listOf("a", "b").joinToString("-")
	}

	fun numbered(name: String, count: Int): String {
		return "%2\$d %1\$s".format(name, count)
	}

	fun specifiers(text: String, value: Double): String {
		return "%.3s %.1e%n".format(text, value)
	}

	fun built(flag: Boolean, ratio: Double): String {
		return buildString {
			append(flag)
			append(ratio)
		}
	}
}
