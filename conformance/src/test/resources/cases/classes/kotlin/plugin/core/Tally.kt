package plugin.core

/** A tally of confirmed modules, as a class with instances. */
class Tally(var label: String) {

	private val confirmed = HashSet<Int>() // sprite ID -> true
	private var count = START
	private var locked = false

	/** Confirms a module once; confirming it again changes nothing. */
	fun confirm(sprite: Int) {
		if (locked || sprite in confirmed) {
			return
		}
		confirmed.add(sprite)
		bump()
	}

	fun total(): Int {
		return count
	}

	fun lock() {
		locked = true
	}

	private fun bump() {
		count++
	}

	companion object {

		/** Every tally starts here. */
		const val START = 0

		fun named(label: String): Tally {
			return Tally(label)
		}
	}
}
