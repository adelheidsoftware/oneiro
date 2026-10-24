package plugin.core

/** A board of cells, with the cell class nested inside. */
class Board {

	/** One cell of the board. */
	class Cell(private val index: Int) {

		var marked = false

		fun mark() {
			marked = true
		}

		fun label(): String {
			return Format.cell(index, marked)
		}
	}

	/** Text helpers, private to the board. */
	private object Format {

		fun cell(index: Int, marked: Boolean): String {
			return if (marked) "[$index]" else " $index "
		}
	}

	private val cells = ArrayList<Cell>()

	init {
		for (index in 0 until SIZE) {
			cells.add(Cell(index))
		}
	}

	fun marked(): Int {
		var count = 0
		for (cell in cells) {
			if (cell.marked) {
				count++
			}
		}
		return count
	}

	fun mark(index: Int) {
		cells[index].mark()
	}

	fun first(): Cell {
		return cells[0]
	}

	companion object {

		/** How many cells a new board has. */
		private const val SIZE = 3
	}
}
