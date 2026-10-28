package plugin

import org.adelheid.oneiro.rs3.Globals.log

/** Values Lua reads twice, or makes with statements, read into a local before the statement that uses them. */
object Hoisting {

	/** How a monster feels. */
	enum class Mood { CALM, ANGRY, AFRAID }

	/** Logs how the monster feels: a switch inside a call. */
	fun report(mood: Mood) {
		log("The monster is " + when (mood) {
			Mood.CALM -> "calm"
			Mood.ANGRY -> "angry"
			Mood.AFRAID -> "afraid"
		})
	}

	private fun confirmed(on: Boolean): Boolean = on

	/** Whether to warn: a conditional of booleans inside a call. */
	fun warn(mood: Mood, setting: Boolean): Boolean {
		val warning = confirmed(if (mood == Mood.ANGRY) true else setting)
		return warning
	}

	/** How many names are empty, logged: a stream inside a call. */
	fun empties(names: List<String>) {
		log("${names.count { name -> name.isEmpty() }} empty")
	}

	/** How many names are long and how many short: two streams in one call. */
	fun sizes(names: List<String>) {
		log("${names.count { name -> name.length > 5 }} long, ${names.count { name -> name.length <= 5 }} short")
	}

	/** Whether the wanted drop dropped, when one is wanted: a stream on the right of &&. */
	fun dropped(drops: List<String>, wanted: String): Boolean =
		wanted.isNotEmpty() && drops.any { drop -> drop == wanted }

	/** The changes, or "nothing": a conditional with a stream in a branch. */
	fun changed(changes: List<String>): String =
		if (changes.isEmpty()) "nothing" else changes.joinToString(", ") { change -> change.uppercase() }

	/** Each entry as a line of the ledger. */
	fun detailed(entries: List<String>): List<String> = entries.map { entry -> "- " + entry }

	/** A header and then the ledger's lines, a call's list added to another. */
	fun ledger(entries: List<String>, header: String): List<String> {
		val lines = mutableListOf<String>()
		lines.add(header)
		lines.addAll(detailed(entries))
		return lines
	}

	/** The middle of a text. */
	fun middle(text: String): Int = text.length / 2

	/** The char after a text's middle, at a position worked out once. */
	fun afterMiddle(text: String): Char = text[middle(text) + 1]

	/** Adds one to the count at a text's middle, a position worked out once. */
	fun bump(counts: IntArray, text: String) {
		counts[middle(text)] += 1
	}
}
