package plugin

/** A count that can double: a class, for its function. */
data class Tally(val count: Int) {

	/** Twice the count. */
	fun doubled(): Int = count * 2
}
