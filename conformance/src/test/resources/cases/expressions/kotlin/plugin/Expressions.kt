package plugin

/** Expressions: literals, operators, conditionals, conversions and arrays. */
object Expressions {

	fun integers(): LongArray {
		return longArrayOf(42, 42L, 1_000, 0xFF, 0b101, 15, -7)
	}

	fun decimals(): DoubleArray {
		return doubleArrayOf(1.5, 1.5f.toDouble(), 1e3, 2.0, 0.5, 3.0, 16.0)
	}

	fun texts(): Array<String> {
		return arrayOf("text\n", "é", "say \"hi\"", """
			first
			second
		""".trimIndent())
	}

	fun nothing(): Any? {
		return null
	}

	fun logic(a: Boolean, b: Boolean, x: Int): Boolean {
		val both = a and b
		val either = a or b
		val differ = a xor b
		return both || either && !differ || x > 3
	}

	fun bits(x: Int): Int {
		return (x and 0xF) or (x xor 3) or x.inv() or (x shl 2)
	}

	fun conditional(n: Int, name: String?): String {
		val sign = if (n < 0) "negative" else "positive"
		val shown = name ?: "nobody"
		return "$sign $shown"
	}

	fun casts(d: Double, big: Long): Long {
		val truncated = d.toInt()
		val widened: Long = truncated.toLong()
		val asDouble = big.toDouble()
		val boxed: Any = "text"
		return widened + asDouble.toLong()
	}

	fun arrays(n: Int): Int {
		val values = intArrayOf(1, 2, 3)
		val flags = BooleanArray(n)
		val zeros = DoubleArray(2)
		values[0] = values[2] + values.size
		return flags.size + zeros.size + values[0]
	}

	fun limits(): LongArray {
		return longArrayOf(Int.MAX_VALUE.toLong(), Int.MIN_VALUE.toLong(), Long.MAX_VALUE, Long.MIN_VALUE)
	}
}
