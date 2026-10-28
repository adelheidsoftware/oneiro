package plugin

/** A panel of sections, each with its lines. */
object Panel {

	/** How many lines a section shows. */
	fun show(section: String, lines: List<String>): Int = lines.size

	/** The session's section, whose lines are too long for one line together. */
	fun session(xp: Int, levels: Int): Int = show("session", listOf("experience gained in the session so far: $xp",
		"levels gained in the session so far: $levels", "the worth of the backpack, at its items' values"))
}
