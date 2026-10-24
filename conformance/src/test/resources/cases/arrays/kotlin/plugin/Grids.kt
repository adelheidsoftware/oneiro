package plugin

/** Arrays of more than one dimension, and functions that take any number of values. */
object Grids {

	/** A board of the size, every square empty. */
	fun board(rows: Int, columns: Int): Array<BooleanArray> {
		return Array(rows) { BooleanArray(columns) }
	}

	/** Marks the square of the board. */
	fun mark(board: Array<BooleanArray>, row: Int, column: Int) {
		board[row][column] = true
	}

	/** The sum of the numbers, however many there are. */
	fun total(vararg numbers: Int): Int {
		var sum = 0
		for (number in numbers) {
			sum += number
		}
		return sum
	}

	/** The total of a few numbers and of an array of them. */
	fun both(more: IntArray): Int {
		return total(1, 2) + total(*more)
	}

	/** The label with the parts after it, such as "herbs: guam, tarromin". */
	fun listed(label: String, vararg parts: String): String {
		return "$label: ${parts.joinToString(", ")}"
	}
}
