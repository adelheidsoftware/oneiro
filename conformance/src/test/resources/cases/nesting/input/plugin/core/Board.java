package plugin.core;

import java.util.ArrayList;
import java.util.List;

/** A board of cells, with the cell class nested inside. */
public final class Board {

	/** One cell of the board. */
	public static final class Cell {

		private final int index;
		private boolean marked;

		Cell(int index) {
			this.index = index;
		}

		void mark() {
			marked = true;
		}

		public String label() {
			return Format.cell(index, marked);
		}
	}

	/** Text helpers, private to the board. */
	private static final class Format {

		private Format() {
		}

		static String cell(int index, boolean marked) {
			return marked ? "[" + index + "]" : " " + index + " ";
		}
	}

	/** How many cells a new board has. */
	private static final int SIZE = 3;

	private final List<Cell> cells = new ArrayList<>();

	public Board() {
		for (int index = 0; index < SIZE; index++) {
			cells.add(new Cell(index));
		}
	}

	public int marked() {
		int count = 0;
		for (Cell cell : cells) {
			if (cell.marked) {
				count++;
			}
		}
		return count;
	}

	public void mark(int index) {
		cells.get(index).mark();
	}

	public Cell first() {
		return cells.get(0);
	}
}
