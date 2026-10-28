package plugin;

import java.util.List;

/** Values Lua reads twice that only Java has: a pattern's value, a bound reference's object, and & of a call. */
public final class Patterns {

	private Patterns() {
	}

	/** Marks texts with its tag. */
	public static final class Labeller {

		private final String tag;

		public Labeller(String tag) {
			this.tag = tag;
		}

		public String label(String text) {
			return tag + " " + text;
		}
	}

	private static int checks = 0;

	/** A setting's value, which may be text or a number. */
	public static Object setting(String name) {
		return name.equals("mode") ? "fast" : (Object) 0;
	}

	/** A setting's length, or -1 when it is no text: a call's value tested by a pattern. */
	public static int settingLength(String name) {
		if (setting(name) instanceof String text) {
			return text.length();
		}
		return -1;
	}

	/** A labeller of the tag. */
	public static Labeller labeller(String tag) {
		return new Labeller(tag);
	}

	/** The names labelled, by a reference to a method of a call's value. */
	public static List<String> labelled(List<String> names, String tag) {
		return names.stream().map(labeller(tag)::label).toList();
	}

	/** Whether a name is long, counting the checks. */
	private static boolean isLong(String name) {
		checks++;
		return name.length() > 3;
	}

	/** Whether the plugin is ready and the name long, checking the name either way, as Java's & does. */
	public static boolean readyAndLong(boolean ready, String name) {
		return ready & isLong(name);
	}

	/** How many checks the names have had. */
	public static int checkCount() {
		return checks;
	}
}
