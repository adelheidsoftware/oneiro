package plugin;

/** How the tracker reacts to a new reading. */
public enum Mode {
	/** Reacts at once. */
	FAST,
	SAFE, // waits for a second reading
	OFF
}
