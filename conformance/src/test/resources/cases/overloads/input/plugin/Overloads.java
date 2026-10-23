package plugin;

import org.jspecify.annotations.Nullable;

/** Overloads that form a default chain become one Lua function with optional parameters. */
public class Overloads {

	private static final int STEP = 1;

	private int total;

	/** Starts at zero. */
	public Overloads() {
		this(0);
	}

	/** @param start the first total */
	public Overloads(int start) {
		total = start;
	}

	public void add() {
		add(STEP);
	}

	/**
	 * Adds to the total.
	 *
	 * @param amount how much
	 */
	public void add(int amount) {
		add(amount, false);
	}

	/**
	 * Adds to the total, or takes away.
	 *
	 * @param amount how much
	 * @param negative whether to take it away
	 */
	public void add(int amount, boolean negative) {
		total += negative ? -amount : amount;
	}

	public int sum() {
		return total;
	}

	// A null default leaves the parameter nil
	public static String greet(String name) {
		return greet(name, null);
	}

	public static String greet(String name, @Nullable String title) {
		return title == null ? "Hello " + name : "Hello " + title + " " + name;
	}

	private static int clamp(int value) {
		return clamp(value, 10);
	}

	private static int clamp(int value, int limit) {
		return Math.min(value, limit);
	}

	public static int limited(int value) {
		return clamp(value) + clamp(value, 3);
	}
}
