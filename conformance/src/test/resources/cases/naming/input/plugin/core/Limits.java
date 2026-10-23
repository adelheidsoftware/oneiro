package plugin.core;

/** Shared limits. */
public final class Limits {

	public static final int MAX_TRACKED = 3;

	private Limits() {
	}

	public static int capped(int value) {
		return Math.min(value, MAX_TRACKED);
	}
}
