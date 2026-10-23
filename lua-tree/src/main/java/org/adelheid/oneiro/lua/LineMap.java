package org.adelheid.oneiro.lua;

import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The source position each printed Lua line came from: the basis for explaining a client error in the plugin's own
 * Java or Kotlin terms.
 */
public final class LineMap {

	private final List<@Nullable SourcePosition> origins;

	LineMap(List<@Nullable SourcePosition> origins) {
		this.origins = Collections.unmodifiableList(origins);
	}

	/**
	 * The source position of a Lua line.
	 *
	 * @param luaLine the line number in the printed Lua, starting at 1
	 * @return the source position, or null for a blank line or a line without a source, such as the file header
	 */
	public @Nullable SourcePosition sourceOf(int luaLine) {
		if (luaLine < 1 || luaLine > origins.size()) {
			throw new IndexOutOfBoundsException("Line " + luaLine + " of " + origins.size());
		}
		return origins.get(luaLine - 1);
	}

	public int lineCount() {
		return origins.size();
	}
}
