package plugin

/** A name to follow, which may be missing. */
object Targets {

	/** The first name that starts with the prefix, if there is one. */
	fun firstWith(names: List<String>, prefix: String): String? {
		for (name in names) {
			if (name.startsWith(prefix)) {
				return name
			}
		}
		return null
	}

	/** The name to show: the first one with an A, or "nobody". */
	fun shown(names: List<String>): String {
		return firstWith(names, "A") ?: "nobody"
	}

	/** Whether any name starts with the prefix. */
	fun has(names: List<String>, prefix: String): Boolean {
		return firstWith(names, prefix) != null
	}

	/** The length of the first name with a B, or 0. */
	fun length(names: List<String>): Int {
		val found = firstWith(names, "B") ?: return 0
		return found.length
	}

	/** Greets the first name with a C, and the first with a D or nobody. */
	fun greet(names: List<String>, greetings: MutableList<String>) {
		firstWith(names, "C")?.let { name -> greetings.add("hi $name") }
		val other = firstWith(names, "D")
		if (other != null) {
			greetings.add("hey $other")
		} else {
			greetings.add("nobody")
		}
	}

	/** The first name with an E, which must be there. */
	fun required(names: List<String>): String {
		return firstWith(names, "E") ?: throw IllegalStateException("no name with an E")
	}

	/** Whether a setting is on: one nobody set is on. */
	fun enabled(setting: Boolean?): Boolean {
		return setting ?: true
	}
}
