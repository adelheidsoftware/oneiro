/** Levels and experience, as functions and properties outside any class. */
package plugin

/** The highest level a skill reaches. */
const val MAX_LEVEL = 99

/** The lowest level. */
private const val FIRST = 1

/** The level as text, without a star. */
private fun describe(level: Int): String = "level $level"

/** The level, kept between the first and the highest. */
fun clampLevel(level: Int): Int = level.coerceIn(FIRST, MAX_LEVEL)

/** Whether the level is the highest. */
fun isMaxed(level: Int): Boolean = level >= MAX_LEVEL

/** The level as text, with a star when it is the highest. */
fun Int.levelText(): String = if (isMaxed(this)) "$this*" else describe(this)
