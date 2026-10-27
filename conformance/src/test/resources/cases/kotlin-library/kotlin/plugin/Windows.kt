package plugin

/** Kotlin's collection functions that walk a list in windows: chunked, windowed and zip. */
object Windows {

	/** The names in rows of the width. */
	fun rows(names: List<String>, width: Int): List<List<String>> = names.chunked(width)

	/** The names in rows of two, each joined. */
	fun joinedRows(names: List<String>): List<String> = names.chunked(2) { row -> row.joinToString("-") }

	/** How many times a level is higher than the one before. */
	fun rises(levels: List<Int>): Int = levels.windowed(2).count { pair -> pair[0] < pair[1] }

	/** Each name with the level at its position, as pairs. */
	fun paired(names: List<String>, levels: List<Int>): List<Pair<String, Int>> = names.zip(levels)

	/** Each name with the level at its position, as text. */
	fun labels(names: List<String>, levels: List<Int>): List<String> =
		names.zip(levels) { name, level -> "$name $level" }

	/** The names whose level is at least the least. */
	fun atLeast(names: List<String>, levels: List<Int>, least: Int): List<String> =
		names.zip(levels).filter { it.second >= least }.map { it.first }
}
