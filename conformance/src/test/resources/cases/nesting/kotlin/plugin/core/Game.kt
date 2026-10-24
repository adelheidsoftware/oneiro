package plugin.core

/** Uses the board, and its public nested class, from another file. */
object Game {

	fun opening(): String {
		val board = Board()
		board.mark(1)
		val extra = Board.Cell(5)
		return "${board.first().label()}/${board.marked()}/${extra.label()}"
	}
}
