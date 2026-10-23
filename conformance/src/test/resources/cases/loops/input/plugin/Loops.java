package plugin;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loop forms and their Lua equivalents. */
public final class Loops {

	private Loops() {
	}

	public static int sumBelow(int limit) {
		int total = 0;
		for (int i = 0; i < limit; i++) {
			total += i;
		}
		return total;
	}

	public static int countDown(int from) {
		int steps = 0;
		for (int i = from; i >= 1; i--) {
			steps++;
		}
		return steps;
	}

	public static int evens(int limit) {
		int count = 0;
		for (int i = 0; i <= limit; i += 2) {
			count++;
		}
		return count;
	}

	public static int sumOf(List<Integer> values) {
		int total = 0;
		for (int i = 0; i < values.size(); i++) {
			total += values.get(i);
		}
		return total;
	}

	public static int weighted(List<Integer> values) {
		int total = 0;
		for (int i = 0; i < values.size(); i++) {
			total += i * values.get(i);
		}
		return total;
	}

	public static int skipOdd(List<Integer> values) {
		int total = 0;
		for (int value : values) {
			if (value % 2 != 0) {
				continue;
			}
			total += value;
		}
		return total;
	}

	public static int pairs(int size) {
		int found = 0;
		for (int i = 0; i < size; i++) {
			for (int j = 0; j < size; j++) {
				if (i == j) {
					continue;
				}
				found++;
			}
			if (i == 0) {
				continue;
			}
			found++;
		}
		return found;
	}

	public static int halvings(int value) {
		int steps = 0;
		for (int n = value; n > 1; n = n / 2) {
			steps++;
		}
		return steps;
	}

	public static int keySum(Map<String, Integer> scores, Set<String> names) {
		int total = 0;
		for (Map.Entry<String, Integer> entry : scores.entrySet()) {
			if (names.contains(entry.getKey())) {
				total += entry.getValue();
			}
		}
		for (String name : names) {
			total += name.length();
		}
		return total;
	}

	public static int drain(int start) {
		int value = start;
		do {
			value--;
		} while (value > 0);
		return value;
	}
}
