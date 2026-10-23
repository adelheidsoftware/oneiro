package plugin;

import java.util.List;
import java.util.Optional;

/** A name to follow, which may be missing. */
public final class Targets {

	private Targets() {
	}

	/** The first name that starts with the prefix, if there is one. */
	public static Optional<String> firstWith(List<String> names, String prefix) {
		for (String name : names) {
			if (name.startsWith(prefix)) {
				return Optional.of(name);
			}
		}
		return Optional.empty();
	}

	/** The name to show: the first one with an A, or "nobody". */
	public static String shown(List<String> names) {
		return firstWith(names, "A").orElse("nobody");
	}

	/** Whether any name starts with the prefix. */
	public static boolean has(List<String> names, String prefix) {
		return firstWith(names, prefix).isPresent();
	}

	/** The length of the first name with a B, or 0. */
	public static int length(List<String> names) {
		Optional<String> found = firstWith(names, "B");
		if (found.isEmpty()) {
			return 0;
		}
		return found.get().length();
	}

	/** Greets the first name with a C, and the first with a D or nobody. */
	public static void greet(List<String> names, List<String> greetings) {
		firstWith(names, "C").ifPresent(name -> greetings.add("hi " + name));
		Optional<String> other = firstWith(names, "D");
		other.ifPresentOrElse(found -> greetings.add("hey " + found), () -> greetings.add("nobody"));
	}

	/** The first name with an E, which must be there. */
	public static String required(List<String> names) {
		return firstWith(names, "E").orElseThrow(() -> new IllegalStateException("no name with an E"));
	}

	/** Whether a setting is on: one nobody set is on. */
	public static boolean enabled(Optional<Boolean> setting) {
		return setting.orElse(true);
	}
}
