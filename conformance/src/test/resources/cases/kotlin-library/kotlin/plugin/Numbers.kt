package plugin

import kotlin.math.roundToInt

/** Kotlin's functions on numbers, and repeat, takeIf and takeUnless. */
object Numbers {

	/** The level, kept between 1 and 99. */
	fun level(raw: Int): Int = raw.coerceIn(1, 99)

	/** The amount, at least none. */
	fun atLeastNone(amount: Int): Int = amount.coerceAtLeast(0)

	/** The amount, at most the cap. */
	fun capped(amount: Int, cap: Int): Int = amount.coerceAtMost(cap)

	/** The part of the whole, in whole percent, a half rounded up. */
	fun percent(part: Double, whole: Double): Int = (part * 100 / whole).roundToInt()

	/** -1, 0 or 1 as the first level is below, at or above the second. */
	fun order(first: Int, second: Int): Int = first.compareTo(second)

	/** The text, said the given number of times. */
	fun echo(text: String, times: Int): String {
		var said = ""
		repeat(times) {
			said += text
		}
		return said
	}

	/** The indices below the count, added up. */
	fun indexSum(count: Int): Int {
		var total = 0
		repeat(count) { index ->
			total += index
		}
		return total
	}

	/** The level when it is positive, otherwise null. */
	fun positive(level: Int): Int? = level.takeIf { it > 0 }

	/** The name when it is not blank, otherwise null. */
	fun named(name: String): String? = name.takeUnless { it.isBlank() }

	/** The name in lower case, unless it is none. */
	fun lowerName(name: String): String? {
		val lower = name.lowercase().takeIf { it != "none" }
		return lower
	}
}
