package plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Enums: constants as strings, switches over them, and their lists. */
public final class Settings {

	/** How loud a notification is. */
	public enum Volume { QUIET, LOUD }

	private Settings() {
	}

	public static String describe(Mode mode) {
		return switch (mode) {
			case FAST -> "fast";
			case SAFE, OFF -> "careful";
		};
	}

	public static int loudness(Volume volume) {
		int loudness = 0;
		for (Volume each : Volume.values()) {
			if (each == volume || each.name().equals("LOUD")) {
				loudness++;
			}
		}
		return loudness;
	}

	public static Mode parse(String name) {
		return Mode.valueOf(name);
	}

	public static String report(Mode mode, Volume volume) {
		return "mode " + mode + " at " + volume.toString();
	}

	public static Map<Mode, Integer> tally(List<Mode> modes) {
		Map<Mode, Integer> tally = new HashMap<>();
		for (Mode mode : modes) {
			tally.put(mode, tally.getOrDefault(mode, 0) + 1);
		}
		return tally;
	}
}
