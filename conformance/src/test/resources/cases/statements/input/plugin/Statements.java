package plugin;

import org.jspecify.annotations.Nullable;

/** Statements: locals, assignments, branches, loops, throws and printing. */
public final class Statements {

	/** The squares of 1 to 3, filled in when the module loads. */
	public static final int[] SQUARES = new int[3];

	static {
		for (int i = 0; i < SQUARES.length; i++) {
			SQUARES[i] = (i + 1) * (i + 1);
		}
	}

	private Statements() {
	}

	public static int locals(int seed) {
		int a = seed, b = a + 1;
		int c;
		var d = a * b;
		c = d - a;
		c += 2;
		c -= 1;
		c *= 3;
		c <<= 1;
		{
			int scoped = c;
			c = scoped + 1;
		}
		return c;
	}

	public static String grade(int score) {
		if (score >= 90) {
			return "A";
		} else if (score >= 50) {
			return "B";
		} else {
			return "C";
		}
	}

	public static int firstPowerAbove(int limit) {
		int power = 1;
		while (true) {
			if (power > limit) {
				break;
			}
			power *= 2;
		}
		return power;
	}

	public static int checked(int value) {
		if (value < 0) {
			throw new IllegalArgumentException("negative: " + value);
		}
		if (value > 100) {
			throw new IllegalStateException();
		}
		return value;
	}

	public static void report(String name, int count, double ratio, boolean done) {
		System.out.println(name);
		System.out.println(count);
		System.out.println(ratio);
		System.out.println(done);
		System.out.println();
		System.out.println("done: " + done);
	}

	// A conditional whose true value may be nil is an if
	public static @Nullable String pick(boolean first, @Nullable String a, String b) {
		String chosen = first ? a : b;
		return chosen;
	}

	public static @Nullable String size(int n, @Nullable String small) {
		return n < 10 ? small : n < 100 ? null : "large";
	}
}
