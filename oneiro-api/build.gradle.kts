// What plugin authors compile against: the Plugin base class, @Subscribe and the settings annotations, the
// annotations that describe existing Lua, and LuaString for Lua's string patterns. It holds nothing derived from
// rs3-api-types' stubs.

plugins {
	`java-library`
}

description = "The annotations and base classes that RS3 plugins written in Java compile against."
