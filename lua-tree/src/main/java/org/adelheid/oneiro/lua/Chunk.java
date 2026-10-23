package org.adelheid.oneiro.lua;

import java.util.List;

/**
 * One Lua file: the header comment that every file in the house style starts with, then its statements. A module's
 * final {@code return Module} is an ordinary return statement at the end of the body.
 *
 * @param header the header comment lines; the printer separates them from the body with a blank line
 * @param body the file's statements
 */
public record Chunk(List<Comment> header, Block body) {

	public Chunk {
		header = List.copyOf(header);
	}
}
