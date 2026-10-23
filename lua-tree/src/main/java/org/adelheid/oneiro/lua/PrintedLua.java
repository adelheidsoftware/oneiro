package org.adelheid.oneiro.lua;

/**
 * A printed Lua file.
 *
 * @param text the Lua source, ending with a line break
 * @param lineMap the source position of each of its lines
 */
public record PrintedLua(String text, LineMap lineMap) {
}
