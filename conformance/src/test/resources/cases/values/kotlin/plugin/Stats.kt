package plugin

import org.adelheid.oneiro.api.LuaModule
import org.adelheid.oneiro.api.LuaReturnValues

/** The hand-written statistics module. */
@LuaModule("src/stats")
object Stats {

	/** What extremes returns, declared with the function. */
	@LuaReturnValues
	data class Extremes(val least: Int, val most: Int)

	external fun summarise(values: List<Int>): Summary

	external fun extremes(values: List<Int>): Extremes
}
