package plugin

/** Interop features used where Lua cannot follow. */
object Misuse {

	/** A data class with a function of its own. */
	data class Point(val x: Int, val y: Int) {
		fun sum(): Int {
			return x + y
		}
	}

	external fun outside(): Int

	fun describe(point: Point): String {
		return "point $point"
	}

	fun hashOf(count: Int): Int {
		return count.hashCode()
	}

	fun firstSeen(names: List<String>): List<String> {
		val seen = mutableSetOf<String>()
		return names.filter(seen::add)
	}
}
