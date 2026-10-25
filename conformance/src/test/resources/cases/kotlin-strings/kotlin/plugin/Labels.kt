package plugin

/** Kotlin's own string functions: padding, prefixes and suffixes, the text around a delimiter, and blank text. */
object Labels {

	/** A time of day as a clock shows it, 09:05. */
	fun clock(hours: Int, minutes: Int): String {
		return hours.toString().padStart(2, '0') + ":" + minutes.toString().padStart(2, '0')
	}

	/** The name in a column ten wide. */
	fun column(name: String): String {
		return name.padEnd(10) + "|"
	}

	/** An item's name without its article and its plural's s. */
	fun item(text: String): String {
		return text.removePrefix("a ").removeSuffix("s")
	}

	/** The command in a line such as "teleport:varrock". */
	fun command(line: String): String = line.substringBefore(":")

	/** The argument in a line such as "teleport:varrock". */
	fun argument(line: String): String = line.substringAfter(":")

	/** Whether the line has text other than spaces. */
	fun hasText(line: String): Boolean = line.isNotBlank()

	/** Whether the line has nothing but spaces. */
	fun isEmptyLine(line: String): Boolean = line.isBlank()

	/** The line without its leading spaces. */
	fun indentless(line: String): String = line.trimStart()

	/** The line without its trailing spaces. */
	fun untrailed(line: String): String = line.trimEnd()

	/** The first three letters of a name. */
	fun short(name: String): String = name.take(3)

	/** A name after its first three letters. */
	fun rest(name: String): String = name.drop(3)
}
