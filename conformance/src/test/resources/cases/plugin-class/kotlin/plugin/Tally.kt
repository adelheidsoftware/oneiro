package plugin

/** A running total. */
class Tally {

	private var total = 0

	fun add(amount: Int) {
		total += amount
	}

	fun sum(): Int = total
}
