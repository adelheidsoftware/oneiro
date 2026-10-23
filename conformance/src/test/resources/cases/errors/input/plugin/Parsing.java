package plugin;

import static org.adelheid.oneiro.rs3.Globals.log;

/** Errors caught with pcall. */
public final class Parsing {

	private static int failures;

	private Parsing() {
	}

	private static int measured(String text) {
		if (text.isEmpty()) {
			throw new IllegalArgumentException("empty text");
		}
		if (text.startsWith("!")) {
			throw new IllegalArgumentException("not a measure: " + text);
		}
		return text.length();
	}

	/**
	 * Runs the task, and logs and counts a failure instead of passing it on.
	 *
	 * @param task what to run
	 */
	public static void run(Runnable task) {
		try {
			task.run();
		} catch (RuntimeException e) {
			failures++;
			log("The task failed:", e.getMessage());
		}
	}

	public static int number(String text, int fallback) {
		try {
			return measured(text);
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	public static String outcome(String text) {
		try {
			int value = measured(text);
			return "number " + value;
		} catch (Exception e) {
			// The message says what went wrong
			return "error " + e.getMessage();
		}
	}

	public static void strict(String text) {
		try {
			measured(text);
		} catch (RuntimeException e) {
			if (e.getMessage().contains("empty")) {
				return;
			}
			throw e;
		}
	}

	public static int failureCount() {
		return failures;
	}

	public static void check(String problem) {
		IllegalStateException failure = new IllegalStateException("broken: " + problem);
		if (!problem.isEmpty()) {
			throw failure;
		}
	}

	public static String message(String problem) {
		RuntimeException failure = new RuntimeException(problem);
		return failure.getMessage();
	}
}
