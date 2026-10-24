package plugin

/** Integer arithmetic keeps Kotlin's rounding toward zero. */
object Arithmetic {

	private const val SLOTS = 5

	/** Kotlin's / on numbers that may be negative. */
	fun divide(a: Int, b: Int): Int {
		return a / b
	}

	/** Kotlin's % on numbers that may be negative. */
	fun remainder(a: Int, b: Int): Int {
		return a % b
	}

	fun half(items: List<Int>): Int {
		return items.size / 2 + items.size % 2
	}

	fun shifted(value: Int): Int {
		return (value shr 4) + (value ushr 28) + (value shl 1) + (SLOTS shr 1)
	}

	fun ratio(part: Int, whole: Double): Double {
		return part / whole
	}

	fun truncated(value: Double): Int {
		return value.toInt()
	}

	fun floored(a: Int, b: Int): Int {
		return a.floorDiv(b) + a.mod(b)
	}
}
