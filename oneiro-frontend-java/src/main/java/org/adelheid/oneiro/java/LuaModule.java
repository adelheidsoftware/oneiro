package org.adelheid.oneiro.java;

import org.adelheid.oneiro.lua.Chunk;

/**
 * One generated Lua file.
 *
 * @param path the file's path inside the plugin, such as {@code src/core/row.lua}
 * @param chunk the file's Lua tree, ready for {@code LuaPrinter}
 */
public record LuaModule(String path, Chunk chunk) {
}
