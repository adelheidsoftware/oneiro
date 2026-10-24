package plugin

import org.adelheid.oneiro.api.LuaReturnValues
import org.adelheid.oneiro.rs3.World

/** Several values returned from Lua functions, and returned to Lua. */
object Heights {

	/** The smallest and the largest of some values. */
	@LuaReturnValues
	data class Span(val low: Int, val high: Int)

	/**
	 * The height of the ground, or the fallback where the world has none.
	 *
	 * @param fallback the height to use instead
	 */
	fun groundOr(x: Int, z: Int, fallback: Int): Int {
		val ground = World.GetGroundHeight(0, x, z)
		if (!ground.available()) {
			return fallback
		}
		return ground.height()
	}

	fun lowest(x: Int, z: Int): Int {
		val here = World.GetGroundHeight(0, x, z)
		val east = World.GetGroundHeight(0, x + 512, z)
		return minOf(here.height(), east.height())
	}

	/** The span of the values. */
	fun span(values: List<Int>): Span {
		var low = values[0]
		var high = low
		for (value in values) {
			low = minOf(low, value)
			high = maxOf(high, value)
		}
		return Span(low, high)
	}

	fun width(values: List<Int>): Int {
		val (low, high) = span(values)
		return high - low
	}

	fun spanOfAll(values: List<Int>): Span {
		return span(values)
	}

	fun checkedSpan(values: List<Int>): Span {
		val span = span(values)
		if (span.low < 0) {
			throw IllegalArgumentException("negative values")
		}
		return span
	}

	fun report(values: List<Int>): String {
		val (count, mean) = Stats.summarise(values)
		return "$count values, mean $mean"
	}

	fun range(values: List<Int>): Int {
		val extremes = Stats.extremes(values)
		return extremes.most - extremes.least
	}
}
