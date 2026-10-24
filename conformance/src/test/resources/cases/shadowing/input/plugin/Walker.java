package plugin;

/** Locals named like the file's private members, which share one namespace with them in Lua. */
public final class Walker {

	private static int total = 10;

	private Walker() {
	}

	/** The steps from n down to 1. */
	public static int countDown(int n) {
		return step(n);
	}

	private static int step(int n) {
		int step = n - 1;
		if (step <= 0) {
			return 0;
		}
		return 1 + step(step);
	}

	/** The amount added to the running total, which this method reads by its qualified name. */
	public static int plusTotal(int amount) {
		int total = amount;
		return total + Walker.total;
	}

	/** Twice the steps; a parameter named like a private method this method does not use keeps its name. */
	public static int doubled(int step) {
		return step * 2;
	}
}
