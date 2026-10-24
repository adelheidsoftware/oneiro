package plugin

/** Enums: constants as strings, when over them, and their lists. */
object Settings {

	/** How loud a notification is. */
	enum class Volume { QUIET, LOUD }

	fun describe(mode: Mode): String {
		return when (mode) {
			Mode.FAST -> "fast"
			Mode.SAFE, Mode.OFF -> "careful"
		}
	}

	fun loudness(volume: Volume): Int {
		var loudness = 0
		for (each in Volume.entries) {
			if (each == volume || each.name == "LOUD") {
				loudness++
			}
		}
		return loudness
	}

	fun parse(name: String): Mode {
		return Mode.valueOf(name)
	}

	fun report(mode: Mode, volume: Volume): String {
		return "mode $mode at ${volume.name}"
	}

	fun tally(modes: List<Mode>): Map<Mode, Int> {
		val tally = HashMap<Mode, Int>()
		for (mode in modes) {
			tally[mode] = (tally[mode] ?: 0) + 1
		}
		return tally
	}
}
