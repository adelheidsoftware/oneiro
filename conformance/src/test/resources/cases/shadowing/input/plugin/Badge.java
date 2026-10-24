package plugin;

/** A badge of some type, at a level. */
public record Badge(String type, int level) {

	public Badge {
		if (level < 0) {
			throw new IllegalArgumentException("level " + level);
		}
	}

	/** The badge as text, such as "gold 2". */
	public String describe() {
		return type + " " + level;
	}
}
