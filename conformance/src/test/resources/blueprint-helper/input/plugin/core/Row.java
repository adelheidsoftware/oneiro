package plugin.core;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A row is the five track slots of the Discovery window as the reader sees them: a list of five sprite IDs, -1 where
 * a slot is empty. This is the one place that says when a row is complete.
 */
public final class Row {

	private Row() {
	}

	/**
	 * @param row five sprite IDs, -1 for an empty slot
	 * @return complete: true when every one of the five slots holds a module
	 */
	public static boolean complete(@Nullable List<Integer> row) {
		if (row == null) {
			return false;
		}
		for (int slot = 0; slot < 5; slot++) {
			// A hole in the list or a negative ID both mean the slot is empty
			Integer id = row.get(slot);
			if (id == null || id < 0) {
				return false;
			}
		}
		return true;
	}
}
