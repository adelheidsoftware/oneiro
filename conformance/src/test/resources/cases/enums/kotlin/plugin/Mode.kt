package plugin

/** How the tracker reacts to a new reading. */
enum class Mode {
	/** Reacts at once. */
	FAST,
	SAFE, // waits for a second reading
	OFF,
}
