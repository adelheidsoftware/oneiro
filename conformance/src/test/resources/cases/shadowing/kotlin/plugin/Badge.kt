package plugin

/** A badge of some type, at a level. */
data class Badge(val type: String, val level: Int) {

	init {
		if (level < 0) {
			throw IllegalArgumentException("level $level")
		}
	}

	/** The badge as text, such as "gold 2". */
	fun describe(): String {
		return "$type $level"
	}
}
