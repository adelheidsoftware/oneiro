/** Levels of a skill. */
package plugin

/** The highest level a skill reaches. */
const val MAX_LEVEL = 99

/** The level, no higher than the highest. */
fun clamp(level: Int): Int = minOf(level, MAX_LEVEL)

/** The level as text. */
fun Int.levelText(): String = "level $this"
