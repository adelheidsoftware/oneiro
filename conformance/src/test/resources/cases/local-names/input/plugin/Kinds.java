package plugin;

/** The kind of the value last looked at. */
public final class Kinds {

	private Kinds() {
	}

	private static Object current = 0;

	/** Looks at a value. */
	public static void look(Object value) {
		current = value;
	}

	/** What the log calls the value looked at: its kind. */
	public static String log() {
		return current instanceof Integer ? "number" : "other";
	}
}
