package plugin;

/** Arrays of more than one dimension, and methods that take any number of values. */
public final class Grids {

	private Grids() {
	}

	/** A board of the size, every square empty. */
	public static boolean[][] board(int rows, int columns) {
		return new boolean[rows][columns];
	}

	/** Marks the square of the board. */
	public static void mark(boolean[][] board, int row, int column) {
		board[row][column] = true;
	}

	/** The sum of the numbers, however many there are. */
	public static int total(int... numbers) {
		int sum = 0;
		for (int number : numbers) {
			sum += number;
		}
		return sum;
	}

	/** The total of a few numbers and of an array of them. */
	public static int both(int[] more) {
		return total(1, 2) + total(more);
	}

	/** The label with the parts after it, such as "herbs: guam, tarromin". */
	public static String listed(String label, String... parts) {
		return label + ": " + String.join(", ", parts);
	}
}
