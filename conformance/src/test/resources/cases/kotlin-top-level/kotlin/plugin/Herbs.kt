package plugin

private const val SEPARATOR = ", "

/** The name in lower case. */
private fun tidy(name: String): String = name.lowercase()

/** Herb names, tidied by a function private to this file. */
object Herbs {

	/** The names, tidied and joined. */
	fun joined(names: List<String>): String = names.joinToString(SEPARATOR) { tidy(it) }
}
