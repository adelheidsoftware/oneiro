package plugin.core

import plugin.core.Limits.MAX_TRACKED
import plugin.core.Limits.capped

/** Names: an acronym in the file name, Lua keywords and globals as local names, and static imports. */
object NPCTracker {

	fun track(names: List<String>): Int {
		val table = HashMap<String, Int>()
		for (end in names) {
			table[end] = capped(names.size)
		}
		val count = table.size
		return count + MAX_TRACKED
	}
}
