package plugin;

import java.util.List;

/** Integer arithmetic keeps Java's rounding toward zero. */
public final class Arithmetic {

	private static final int SLOTS = 5;

	private Arithmetic() {
	}

	/** Java's / on numbers that may be negative. */
	public static int divide(int a, int b) {
		return a / b;
	}

	/** Java's % on numbers that may be negative. */
	public static int remainder(int a, int b) {
		return a % b;
	}

	public static int half(List<Integer> items) {
		return items.size() / 2 + items.size() % 2;
	}

	public static int shifted(int value) {
		return (value >> 4) + (value >>> 28) + (value << 1) + (SLOTS >> 1);
	}

	public static double ratio(int part, double whole) {
		return part / whole;
	}

	public static int truncated(double value) {
		return (int) value;
	}

	public static int floored(int a, int b) {
		return Math.floorDiv(a, b) + Math.floorMod(a, b);
	}
}
