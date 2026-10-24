package plugin

/** When as if/elseif chains. */
object Switches {

	// A when with several values on one branch, and an else
	fun describe(code: Int): String {
		val text: String
		when (code) {
			// The common case first
			1 -> text = "one"
			2, 3 -> text = "a few"
			else -> text = "many"
		}
		return text
	}

	// Values that share their code are listed on one branch
	fun weight(size: String): Int {
		var weight = 0
		when (size) {
			"small" -> weight = 1
			"medium", "large" -> {
				// Both count as big
				weight = 5
			}
			else -> weight = -1
		}
		return weight
	}

	// A boolean from each branch
	fun isWeekend(day: String): Boolean {
		return when (day) {
			"sat", "sun" -> true
			else -> false
		}
	}

	// A subject Lua computes is read once, into a local
	fun amount(values: List<Int>): String {
		return when (values.size) {
			0 -> "none"
			1 -> "one"
			else -> "several"
		}
	}

	// Whatever computes it, when more than one condition compares it
	fun parity(value: Int): String {
		return when (value % 2) {
			0 -> "even"
			1, -1 -> "odd"
			else -> "impossible"
		}
	}

	// A when as the value of a local, with a branch that computes its value in a block
	fun price(item: String, member: Boolean): Int {
		val price = when (item) {
			"apple" -> 3
			"pear" -> {
				val base = 4
				if (member) base - 1 else base
			}
			else -> 0
		}
		return price
	}

	// Returned directly
	fun season(month: Int): String {
		return when (month) {
			12, 1, 2 -> "winter"
			3, 4, 5 -> "spring"
			6, 7, 8 -> "summer"
			else -> "autumn"
		}
	}

	// Assigned
	fun label(level: Int): String {
		var label = "unknown"
		label = when (level) {
			0 -> "perfect"
			5 -> "poor"
			else -> "level $level"
		}
		return label
	}

	// Inside a loop, where continue still jumps to the next value
	fun sumOdd(values: List<Int>): Int {
		var sum = 0
		for (value in values) {
			when (value % 2) {
				0 -> continue
				else -> sum += value
			}
		}
		return sum
	}
}
