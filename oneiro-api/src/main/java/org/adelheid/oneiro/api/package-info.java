/**
 * What plugin authors compile against: the {@code Plugin} and {@code DevPlugin} base classes, {@code Subscribe} for
 * events, the settings annotations {@code ConfigGroup} and {@code ConfigItem}, {@code LuaString} for Lua's string
 * patterns, {@code LuaName} and {@code LuaReturnValues}, which say how the plugin's own classes meet Lua, and the
 * annotations that only generated bindings carry, which tell the compiler how Lua calls the members of the RS3 API and
 * of the plugin's own Lua.
 */
@NullMarked
package org.adelheid.oneiro.api;

import org.jspecify.annotations.NullMarked;
