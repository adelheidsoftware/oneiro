package plugin

/** What to say about the creatures met. */
object Encounters {

	/** How dangerous a creature is, in a word. */
	fun danger(creature: Creature): String {
		return when (creature) {
			is Boss -> "deadly ${creature.name}"
			is Monster if creature.level > 100 -> "strong ${creature.name}"
			is Monster -> "weak ${creature.name}"
			is Pet -> "friendly ${creature.name}"
			else -> "unknown"
		}
	}

	/** The combat level of a creature that fights, or 0. */
	fun level(creature: Creature): Int {
		if (creature is Monster) {
			return creature.level
		}
		return 0
	}

	/** Whether the value is text. */
	fun isText(value: Any): Boolean {
		return value is String
	}
}
