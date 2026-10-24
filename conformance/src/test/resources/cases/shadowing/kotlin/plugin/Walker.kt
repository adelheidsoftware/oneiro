package plugin

/** Locals named like the file's private members, which share one namespace with them in Lua. */
object Walker {

	private val total = 10

	/** The steps from n down to 1. */
	fun countDown(n: Int): Int {
		return step(n)
	}

	private fun step(n: Int): Int {
		val step = n - 1
		if (step <= 0) {
			return 0
		}
		return 1 + step(step)
	}

	/** The amount added to the running total, which this function reads by its qualified name. */
	fun plusTotal(amount: Int): Int {
		val total = amount
		return total + Walker.total
	}

	/** Twice the steps; a parameter named like a private function this function does not use keeps its name. */
	fun doubled(step: Int): Int {
		return step * 2
	}
}
