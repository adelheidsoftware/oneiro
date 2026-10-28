package plugin

/** Comparisons of data classes, which Kotlin makes by their properties and Lua by identity. */
object Equality {

	/** A tile of the map. */
	data class Tile(val x: Int, val y: Int)

	/** Whether two tiles are equal. */
	fun same(a: Tile, b: Tile): Boolean = a == b

	/** Whether a value is the tile, with the tile on the right. */
	fun isTile(value: Any, tile: Tile): Boolean = value == tile
}
