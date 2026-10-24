package plugin.core

/**
 * A row is the five track slots of the Discovery window as the reader sees them: a list of five sprite IDs, -1 where
 * a slot is empty. This is the one place that says when a row is complete.
 */
object Row {

	/**
	 * @param row five sprite IDs, -1 for an empty slot
	 * @return complete: true when every one of the five slots holds a module
	 */
	fun complete(row: List<Int?>?): Boolean {
		if (row == null) {
			return false
		}
		for (slot in 0 until 5) {
			// A hole in the list or a negative ID both mean the slot is empty
			val id = row[slot]
			if (id == null || id < 0) {
				return false
			}
		}
		return true
	}
}
