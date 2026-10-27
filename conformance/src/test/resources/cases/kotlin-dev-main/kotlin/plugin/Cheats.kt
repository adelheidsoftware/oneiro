package plugin

/** Dev code that calls the main source set's top-level functions. */
object Cheats {

	/** A level past the highest, clamped. */
	fun clamped(): Int = clamp(120)

	/** A level as text. */
	fun text(level: Int): String = level.levelText()
}
