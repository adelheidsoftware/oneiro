package plugin;

/** Work that always tidies up after itself. */
public final class Cleanup {

	/** How many tasks have run, failed or not. */
	public static int runs;

	private Cleanup() {
	}

	/** A reader of a text, which counts when it is closed. */
	public static final class Reader implements AutoCloseable {

		/** How many readers have been closed. */
		public static int closes;

		private final String text;

		public Reader(String text) {
			this.text = text;
		}

		/** The text's first letter; an empty text has none. */
		public String first() {
			if (text.isEmpty()) {
				throw new IllegalStateException("nothing to read");
			}
			return text.substring(0, 1);
		}

		@Override
		public void close() {
			closes++;
		}
	}

	/** Runs the task, and counts the run even when the task fails. */
	public static void counted(Runnable task) {
		try {
			task.run();
		} finally {
			// A failed run counts too
			runs++;
			// The task's error, if any, goes on to the caller
		}
	}

	/** The text's first letter, or the fallback, counting a run either way. */
	public static String firstOr(String text, String fallback) {
		try {
			return new Reader(text).first();
		} catch (RuntimeException e) {
			return fallback;
		} finally {
			runs++;
		}
	}

	/** The text's first letter, read with a reader that is closed whatever happens. */
	public static String read(String text) {
		try (Reader reader = new Reader(text)) {
			return reader.first();
		}
	}
}
