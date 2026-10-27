package plugin

/** A report of levels, made with the functions of another file. */
object Report {

	/** Each level, kept within bounds, as text. */
	fun lines(levels: List<Int>): String = levels.joinToString(", ") { clampLevel(it).levelText() }

	/** How many of the levels are the highest. */
	fun maxed(levels: List<Int>): Int = levels.count(::isMaxed)

	/** The highest level, as the other file says. */
	fun highest(): Level = MAX_LEVEL
}
