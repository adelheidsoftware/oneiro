package plugin;

import org.jspecify.annotations.Nullable;

/** Expressions: literals, operators, conditionals, casts and arrays. */
public final class Expressions {

	private Expressions() {
	}

	public static long[] integers() {
		return new long[] {42, 42L, 1_000, 0xFF, 0b101, 017, -7};
	}

	public static double[] decimals() {
		return new double[] {1.5, 1.5f, 1e3, 2.0, .5, 3., 0x1p4};
	}

	public static String[] texts() {
		return new String[] {"text\n", "é", "say \"hi\"", """
				first
				second"""};
	}

	public static @Nullable Object nothing() {
		return null;
	}

	public static boolean logic(boolean a, boolean b, int x) {
		boolean both = a & b;
		boolean either = a | b;
		boolean differ = a ^ b;
		return both || either && !differ || x > 3;
	}

	public static int bits(int x) {
		return (x & 0xF) | (x ^ 3) | ~x | x << 2;
	}

	public static String conditional(int n, @Nullable String name) {
		String sign = n < 0 ? "negative" : "positive";
		String shown = name != null ? name : "nobody";
		return sign + " " + shown;
	}

	public static long casts(double d, long big) {
		int truncated = (int) d;
		long widened = truncated;
		double asDouble = big;
		Object boxed = (Object) "text";
		return widened + (long) asDouble;
	}

	public static int arrays(int n) {
		int[] values = {1, 2, 3};
		boolean[] flags = new boolean[n];
		double[] zeros = new double[2];
		values[0] = values[2] + values.length;
		return flags.length + zeros.length + values[0];
	}

	public static long[] limits() {
		return new long[] {Integer.MAX_VALUE, Integer.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE};
	}
}
