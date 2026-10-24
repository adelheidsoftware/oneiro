package plugin

/** Loop forms and their Lua equivalents. */
object Loops {

	fun sumBelow(limit: Int): Int {
		var total = 0
		for (i in 0 until limit) {
			total += i
		}
		return total
	}

	fun countDown(from: Int): Int {
		var steps = 0
		for (i in from downTo 1) {
			steps++
		}
		return steps
	}

	fun evens(limit: Int): Int {
		var count = 0
		for (i in 0..limit step 2) {
			count++
		}
		return count
	}

	fun sumOf(values: List<Int>): Int {
		var total = 0
		for (i in values.indices) {
			total += values[i]
		}
		return total
	}

	fun weighted(values: List<Int>): Int {
		var total = 0
		for (i in values.indices) {
			total += i * values[i]
		}
		return total
	}

	fun skipOdd(values: List<Int>): Int {
		var total = 0
		for (value in values) {
			if (value % 2 != 0) {
				continue
			}
			total += value
		}
		return total
	}

	fun pairs(size: Int): Int {
		var found = 0
		for (i in 0 until size) {
			for (j in 0 until size) {
				if (i == j) {
					continue
				}
				found++
			}
			if (i == 0) {
				continue
			}
			found++
		}
		return found
	}

	fun halvings(value: Int): Int {
		var steps = 0
		var n = value
		while (n > 1) {
			steps++
			n /= 2
		}
		return steps
	}

	fun keySum(scores: Map<String, Int>, names: Set<String>): Int {
		var total = 0
		for ((key, value) in scores) {
			if (key in names) {
				total += value
			}
		}
		for (name in names) {
			total += name.length
		}
		return total
	}

	fun diagonal(rows: Map<String, Map<String, Int>>): Int {
		var total = 0
		for ((key, value) in rows) {
			for ((cellKey, cellValue) in value) {
				if (cellKey == key) {
					total += cellValue
				}
			}
		}
		return total
	}

	fun drain(start: Int): Int {
		var value = start
		do {
			value--
		} while (value > 0)
		return value
	}
}
