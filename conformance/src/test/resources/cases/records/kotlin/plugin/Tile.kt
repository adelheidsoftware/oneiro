package plugin

import kotlin.math.abs
import kotlin.math.max

/** A tile on the map, with the steps to another. */
data class Tile(val x: Int, val y: Int, val plane: Int) {

	init {
		if (plane < 0 || plane > 3) {
			throw IllegalArgumentException("plane $plane")
		}
	}

	/** The number of steps to the other tile, moving diagonally. */
	fun stepsTo(other: Tile): Int {
		return max(abs(x - other.x), abs(y - other.y))
	}

	override fun toString(): String {
		return "$x, $y, $plane"
	}

	companion object {

		/** The tile at the origin of the ground plane. */
		val ORIGIN = Tile(0, 0, 0)

		/** The tile at the coordinates on the ground plane. */
		fun ground(x: Int, y: Int): Tile {
			return Tile(x, y, 0)
		}
	}
}
