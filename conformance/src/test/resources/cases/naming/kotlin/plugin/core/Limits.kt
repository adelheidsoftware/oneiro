package plugin.core

/** Shared limits. */
object Limits {

	const val MAX_TRACKED = 3

	fun capped(value: Int): Int {
		return minOf(value, MAX_TRACKED)
	}
}
