package plugin;

import org.adelheid.oneiro.api.LuaReturnValues;

/** What Stats.summarise returns: how many values, and their mean. */
@LuaReturnValues
public record Summary(int count, double mean) {
}
