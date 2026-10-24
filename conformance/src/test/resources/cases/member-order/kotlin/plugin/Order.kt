package plugin

/** Module-level code runs top to bottom in Lua, so the functions it calls come first. */
object Order {

	/** The first four squares, built when the module loads. */
	val SQUARES = build(4)

	private fun build(count: Int): IntArray {
		val squares = IntArray(count)
		for (i in 0 until count) {
			squares[i] = square(i)
		}
		return squares
	}

	private fun square(value: Int): Int {
		return value * value
	}

	fun isEven(value: Int): Boolean {
		return value == 0 || isOdd(value - 1)
	}

	private fun isOdd(value: Int): Boolean {
		return value != 0 && isEven(value - 1)
	}
}
