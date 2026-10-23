package plugin.core;

/** Uses the board, and its public nested class, from another file. */
public final class Game {

	private Game() {
	}

	public static String opening() {
		Board board = new Board();
		board.mark(1);
		Board.Cell extra = new Board.Cell(5);
		return board.first().label() + "/" + board.marked() + "/" + extra.label();
	}
}
