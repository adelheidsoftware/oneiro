package plugin;

import java.util.List;

/** A panel of sections, each with its lines. */
public final class Panel {

	private Panel() {
	}

	/** How many lines a section shows. */
	public static int show(String section, List<String> lines) {
		return lines.size();
	}

	/** The session's section, whose lines are too long for one line together. */
	public static int session(int xp, int levels) {
		return show("session", List.of("experience gained in the session so far: " + xp,
				"levels gained in the session so far: " + levels, "the worth of the backpack, at its items' values"));
	}
}
