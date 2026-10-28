package plugin

import org.adelheid.oneiro.rs3.Globals.log

/** Values Lua reads twice, or makes with statements, read into a local before the statement that uses them. */
object Hoisting {

	/** Marks texts with its tag. */
	class Labeller(private val tag: String) {

		fun label(text: String): String = "$tag $text"
	}

	private var checks = 0

	/** Each entry as a line of the ledger. */
	fun detailed(entries: List<String>): List<String> = entries.map { "- $it" }

	/** A header and then the ledger's lines, a call's list added to a builder's. */
	fun ledger(entries: List<String>, header: String): List<String> = buildList {
		add(header)
		addAll(detailed(entries))
	}

	/** The length of a word and one, a scope function on a literal. */
	fun wordLength(): Int = 1 + "herbs".let { it.length }

	/** A line of stars, built inside a call. */
	fun stars(count: Int) {
		log(buildString {
			repeat(count) { append("*") }
		})
	}

	/** Two texts built in one statement. */
	fun pair() {
		log(buildString { append("a") } + buildString { append("b") })
	}

	/** A word repeated, built for a local of its own. */
	fun repeated(word: String, times: Int): String {
		val repeated = buildString {
			repeat(times) { append(word) }
		}
		return repeated
	}

	/** The changes, or "nothing", an if with a chain in a branch. */
	fun changed(changes: List<String>): String =
		if (changes.isEmpty()) "nothing" else changes.joinToString(", ") { it.uppercase() }

	/** The changes, or "nothing", logged: the same if inside a call. */
	fun logChanged(changes: List<String>) {
		log(if (changes.isEmpty()) "nothing" else changes.joinToString { it.uppercase() })
	}

	/** The names in brackets, a join with a prefix and a postfix inside a call. */
	fun bracketed(names: List<String>) {
		log(names.joinToString(prefix = "[", postfix = "]"))
	}

	/** The first entry, or null. */
	fun nearest(entries: List<String>): String? = entries.firstOrNull()

	/** The first few entries. */
	fun top(entries: List<String>, count: Int): List<String> = entries.take(count)

	/** The length of the nearest entry, or 0, a safe call on a call's value. */
	fun nearestLength(entries: List<String>): Int = nearest(entries)?.length ?: 0

	/** The length of the top entry, or 0, a safe call on the first of a call's list. */
	fun topLength(entries: List<String>): Int = top(entries, 1).firstOrNull()?.length ?: 0

	/** Whether the ledger has a sensible number of lines, a call's value compared with both ends of a range. */
	fun sensible(entries: List<String>): Boolean = detailed(entries).size in 1..9

	/** A setting's value, which may be text or a number. */
	fun setting(name: String): Any = if (name == "mode") "fast" else 0

	/** A setting as text, or null, a call's value cast safely. */
	fun settingText(name: String): String? = setting(name) as? String

	/** The middle of a text. */
	fun middle(text: String): Int = text.length / 2

	/** The char after a text's middle, at a position worked out once. */
	fun afterMiddle(text: String): Char = text[middle(text) + 1]

	/** A labeller of the tag. */
	fun labeller(tag: String): Labeller = Labeller(tag)

	/** The names labelled, by a reference to a method of a call's value. */
	fun labelled(names: List<String>, tag: String): List<String> = names.map(labeller(tag)::label)

	/** How many entries are long and how many short, two chains in one call. */
	fun sizes(entries: List<String>) {
		log("${entries.count { it.length > 5 }} long, ${entries.count { it.length <= 5 }} short")
	}

	/** A text's length, logged first, a scope function inside an expression. */
	fun logged(text: String): Int = text.apply { log(this) }.length

	/** A name shouted, worked out in steps inside a call. */
	fun shouted(name: String) {
		log(name.let {
			val loud = it.uppercase()
			"$loud!"
		})
	}

	/** Whether a name is long, counting the checks. */
	private fun isLong(name: String): Boolean {
		checks++
		return name.length > 3
	}

	/** Whether the plugin is ready and the name long, checking the name either way, as Kotlin's and does. */
	fun readyAndLong(ready: Boolean, name: String): Boolean = ready and isLong(name)

	/** How many checks the names have had. */
	fun checkCount(): Int = checks
}
