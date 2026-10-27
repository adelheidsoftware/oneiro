package plugin

/** Kotlin's functions on maps and sets: their keys and values, clear, union, intersect and subtract. */
object Sets {

	/** The skills trained, in the order they were first trained. */
	fun skills(xp: Map<String, Int>): Set<String> = xp.keys

	/** The experience in each skill. */
	fun amounts(xp: Map<String, Int>): Collection<Int> = xp.values

	/** How many skills have experience. */
	fun skillCount(xp: Map<String, Int>): Int = xp.keys.size

	/** Whether the skill has experience. */
	fun trained(xp: Map<String, Int>, skill: String): Boolean = skill in xp.keys

	/** The experience in the skill, or none. */
	fun experience(xp: Map<String, Int>, skill: String): Int = xp.getOrElse(skill) { 0 }

	/** Forgets every skill. */
	fun forget(xp: MutableMap<String, Int>) {
		xp.clear()
	}

	/** The skills either player trained. */
	fun either(mine: Set<String>, theirs: Set<String>): Set<String> = mine union theirs

	/** The skills both players trained. */
	fun both(mine: Set<String>, theirs: Set<String>): Set<String> = mine intersect theirs

	/** The skills only the first player trained. */
	fun onlyMine(mine: Set<String>, theirs: Set<String>): Set<String> = mine subtract theirs

	/** The levels by skill, or none. */
	fun levels(levels: Map<String, Int>?): Map<String, Int> = levels.orEmpty()

	/** Whether there are no levels. */
	fun noLevels(levels: Map<String, Int>?): Boolean = levels.isNullOrEmpty()

	/** Each skill at level 1. */
	fun starting(skills: List<String>): Map<String, Int> = buildMap {
		for (skill in skills) {
			put(skill, 1)
		}
	}
}
