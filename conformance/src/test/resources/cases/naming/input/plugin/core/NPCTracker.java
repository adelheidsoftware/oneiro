package plugin.core;

import static plugin.core.Limits.MAX_TRACKED;
import static plugin.core.Limits.capped;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Names: an acronym in the file name, Lua keywords, globals and helpers as local names, and static imports. */
public final class NPCTracker {

	private NPCTracker() {
	}

	public static int track(List<String> names) {
		Map<String, Integer> table = new HashMap<>();
		for (String end : names) {
			String trim = end.trim();
			table.put(trim, capped(names.size()));
		}
		int count = table.size();
		return count + MAX_TRACKED;
	}
}
