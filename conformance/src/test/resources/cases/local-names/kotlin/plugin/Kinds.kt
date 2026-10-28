package plugin

/** The kind of the value last looked at. */
object Kinds {

	private var current: Any = 0

	/** Looks at a value. */
	fun look(value: Any) {
		current = value
	}

	/** What the log calls the value looked at: its kind. */
	fun log(): String = if (current is Int) "number" else "other"
}
