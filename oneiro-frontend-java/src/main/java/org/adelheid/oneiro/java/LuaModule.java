package org.adelheid.oneiro.java;

import java.util.ArrayList;
import java.util.List;
import org.adelheid.oneiro.lua.Block;
import org.adelheid.oneiro.lua.Chunk;
import org.adelheid.oneiro.lua.Comment;

/**
 * One generated Lua file.
 *
 * @param path the file's path inside the plugin, such as {@code src/core/row.lua}
 * @param chunk the file's Lua tree, ready for {@code LuaPrinter}
 */
public record LuaModule(String path, Chunk chunk) {

	/**
	 * A module whose header starts with its path, {@code -- src/core/row.lua}, followed by its description. The client
	 * loads files without names, so its errors name a file by its first line; this way they name it by its path.
	 */
	static LuaModule of(String path, List<Comment> description, Block body) {
		List<Comment> header = new ArrayList<>();
		header.add(Comment.line(path));
		header.addAll(description);
		return new LuaModule(path, new Chunk(header, body));
	}
}
