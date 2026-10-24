package plugin

/**
 * Default arguments become one Lua function with optional parameters.
 *
 * @param start the first total
 */
class Overloads(start: Int = 0) {

	private var total = start

	/**
	 * Adds to the total, or takes away.
	 *
	 * @param amount how much
	 * @param negative whether to take it away
	 */
	fun add(amount: Int = STEP, negative: Boolean = false) {
		total += if (negative) -amount else amount
	}

	fun sum(): Int {
		return total
	}

	companion object {

		private const val STEP = 1

		// A null default leaves the parameter nil
		fun greet(name: String, title: String? = null): String {
			return if (title == null) "Hello $name" else "Hello $title $name"
		}

		private fun clamp(value: Int, limit: Int = 10): Int {
			return minOf(value, limit)
		}

		fun limited(value: Int): Int {
			return clamp(value) + clamp(value, 3)
		}
	}
}
