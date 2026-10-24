package plugin

import org.adelheid.oneiro.api.LuaReturnValues

/** What Stats.summarise returns: how many values, and their mean. */
@LuaReturnValues
data class Summary(val count: Int, val mean: Double)
