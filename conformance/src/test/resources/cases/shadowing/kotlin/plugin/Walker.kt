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

	private fun sum(a: Int, b: Int): Int = a + b

	/** Twice each value, added up, and the sum of one and two. */
	fun doubledSum(values: List<Int>): Int {
		return values.sumOf { value -> value * 2 } + sum(1, 2)
	}

	/** For each factor, the values times it, then the values as they are. */
	fun scaled(list: List<Int>, factors: List<Int>): List<List<Int>> {
		val out = mutableListOf<List<Int>>()
		factors.forEach { factor ->
			out.add(list.map { value -> value * factor })
			out.add(list)
		}
		return out
	}

	/** How many values are below the number of keys. */
	fun belowKeys(values: List<Int>, keys: Map<String, Int>): Int {
		return values.count { value -> value < keys.size }
	}
}
