package plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Lists, maps and sets as plain Lua tables. */
public final class Tables {

	private Tables() {
	}

	public static List<Integer> numbers() {
		List<Integer> numbers = new ArrayList<>();
		numbers.add(3);
		numbers.add(1);
		numbers.add(0, 2);
		numbers.set(1, 5);
		numbers.sort((a, b) -> Integer.compare(a, b));
		return numbers;
	}

	public static boolean inspect(List<Integer> numbers) {
		return numbers.contains(5) && numbers.indexOf(2) == 0 && !numbers.isEmpty() && numbers.get(0) == 2;
	}

	public static int removeFirst(List<Integer> numbers) {
		return numbers.remove(0);
	}

	public static Map<String, Integer> scores() {
		Map<String, Integer> scores = new HashMap<>();
		scores.put("ann", 3);
		scores.put("bob", 5);
		scores.remove("bob");
		return scores;
	}

	public static int lookup(Map<String, Integer> scores, String name) {
		if (!scores.containsKey(name)) {
			return scores.getOrDefault(name, -1) + scores.size();
		}
		return scores.get(name);
	}

	public static Set<Integer> seen(List<Integer> values) {
		Set<Integer> seen = new HashSet<>();
		for (int value : values) {
			seen.add(value);
		}
		seen.remove(0);
		return seen;
	}

	public static boolean has(Set<Integer> seen, int value) {
		return seen.contains(value) && !seen.isEmpty() && seen.size() > 0;
	}

	public static List<String> doubled(List<String> source) {
		List<String> copy = new ArrayList<>(source);
		copy.addAll(source);
		return copy;
	}

	public static List<String> fixed() {
		return List.of("a", "b");
	}

	public static Map<String, Integer> fixedMap() {
		return Map.of("a", 1, "b", 2);
	}

	public static Set<String> fixedSet() {
		return Set.of("x", "y");
	}

	public static List<Integer> sorted(List<Integer> values) {
		List<Integer> copy = new ArrayList<>(values);
		Collections.sort(copy);
		return copy;
	}

	public static int weighed(Map<String, Integer> scores) {
		int total = 0;
		for (String name : scores.keySet()) {
			total += name.length();
		}
		for (int score : scores.values()) {
			total += score;
		}
		return total;
	}
}
