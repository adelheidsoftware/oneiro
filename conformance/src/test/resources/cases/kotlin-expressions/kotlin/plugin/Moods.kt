package plugin

import org.adelheid.oneiro.rs3.Globals.log

/** An if or when with blocks inside an expression, whose value a local before the statement takes. */
object Moods {

	/** How a monster feels. */
	enum class Mood { CALM, ANGRY, AFRAID }

	/** Logs how the monster feels. */
	fun report(mood: Mood) {
		log("The monster is " + when (mood) {
			Mood.CALM -> "calm"
			Mood.ANGRY -> "angry"
			Mood.AFRAID -> "afraid"
		})
	}

	/** A line about a level, with a note on how far it is past the maximum. */
	fun level(level: Int): String {
		return describe(level, if (level > 99) {
			val over = level - 99
			"$over past the maximum"
		} else {
			"normal"
		})
	}

	private fun describe(level: Int, note: String): String = "level $level: $note"

	/** Whether to warn: always for an angry monster, otherwise as the setting says. */
	fun warn(mood: Mood, setting: Boolean): Boolean {
		val warning = confirmed(if (mood == Mood.ANGRY) true else setting)
		return warning
	}

	private fun confirmed(on: Boolean): Boolean = on

	/** Logs both moods in one line. */
	fun both(first: Mood, second: Mood) {
		log(when (first) {
			Mood.CALM -> "calm"
			else -> "upset"
		} + " and " + when (second) {
			Mood.CALM -> "calm"
			else -> "upset"
		})
	}
}
