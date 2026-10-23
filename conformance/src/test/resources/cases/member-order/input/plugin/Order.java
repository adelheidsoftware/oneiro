package plugin;

/** Module-level code runs top to bottom in Lua, so the functions it calls come first. */
public final class Order {

	private Order() {
	}

	/** The first four squares, built when the module loads. */
	public static final int[] SQUARES = build(4);

	private static int[] build(int count) {
		int[] squares = new int[count];
		for (int i = 0; i < count; i++) {
			squares[i] = square(i);
		}
		return squares;
	}

	private static int square(int value) {
		return value * value;
	}

	public static boolean isEven(int value) {
		return value == 0 || isOdd(value - 1);
	}

	private static boolean isOdd(int value) {
		return value != 0 && isEven(value - 1);
	}
}
