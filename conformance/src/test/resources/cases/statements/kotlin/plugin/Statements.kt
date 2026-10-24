package plugin

/** Statements: locals, assignments, branches, loops, throws and printing. */
object Statements {

	/** The squares of 1 to 3, filled in when the module loads. */
	val SQUARES = IntArray(3)

	init {
		for (i in SQUARES.indices) {
			SQUARES[i] = (i + 1) * (i + 1)
		}
	}

	fun locals(seed: Int): Int {
		val a = seed
		val b = a + 1
		var c: Int
		val d = a * b
		c = d - a
		c += 2
		c -= 1
		c *= 3
		c = c shl 1
		run {
			val scoped = c
			c = scoped + 1
		}
		return c
	}

	fun grade(score: Int): String {
		if (score >= 90) {
			return "A"
		} else if (score >= 50) {
			return "B"
		} else {
			return "C"
		}
	}

	fun firstPowerAbove(limit: Int): Int {
		var power = 1
		while (true) {
			if (power > limit) {
				break
			}
			power *= 2
		}
		return power
	}

	fun checked(value: Int): Int {
		if (value < 0) {
			throw IllegalArgumentException("negative: $value")
		}
		if (value > 100) {
			throw IllegalStateException()
		}
		return value
	}

	fun report(name: String, count: Int, ratio: Double, done: Boolean) {
		println(name)
		println(count)
		println(ratio)
		println(done)
		println()
		println("done: $done")
	}

	// A conditional whose true value may be nil is an if
	fun pick(first: Boolean, a: String?, b: String): String? {
		val chosen = if (first) a else b
		return chosen
	}

	fun size(n: Int, small: String?): String? {
		return if (n < 10) small else if (n < 100) null else "large"
	}
}
