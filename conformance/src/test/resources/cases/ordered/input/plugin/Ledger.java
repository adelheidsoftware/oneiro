package plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Drops in the order they came, and in sorted order. */
public final class Ledger {

	private Ledger() {
	}

	/** The drops' names in the order they first dropped. */
	public static List<String> firstSeen(List<String> drops) {
		Set<String> seen = new LinkedHashSet<>();
		for (String drop : drops) {
			seen.add(drop);
		}
		List<String> names = new ArrayList<>();
		for (String name : seen) {
			names.add(name);
		}
		return names;
	}

	/** How many of each drop, in the order they first dropped, as Java writes a map. */
	public static String tally(List<String> drops) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (String drop : drops) {
			counts.merge(drop, 1, Integer::sum);
		}
		return counts.toString();
	}

	/** The drops still owed after the paid one, which is owed again last. */
	public static List<String> owed(List<String> drops, String paid) {
		Map<String, Integer> owed = new LinkedHashMap<>();
		for (String drop : drops) {
			owed.put(drop, 1);
		}
		owed.remove(paid);
		owed.put(paid, 1);
		List<String> order = new ArrayList<>();
		for (String drop : owed.keySet()) {
			order.add(drop);
		}
		return order;
	}

	/** The drops in alphabetical order, each once, as Java writes a set. */
	public static String sorted(List<String> drops) {
		Set<String> names = new TreeSet<>();
		for (String drop : drops) {
			names.add(drop);
		}
		return names.toString();
	}

	/** The drops by name, last first, with how many of each. */
	public static List<String> reversed(Map<String, Integer> counts) {
		Map<String, Integer> sorted = new TreeMap<>(Comparator.reverseOrder());
		counts.forEach((name, count) -> sorted.put(name, count));
		List<String> lines = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : sorted.entrySet()) {
			lines.add(entry.getKey() + " x" + entry.getValue());
		}
		return lines;
	}

	/** The first and last drop by name, such as "ashes..coins". */
	public static String ends(List<String> drops) {
		TreeSet<String> names = new TreeSet<>(drops);
		return names.first() + ".." + names.last();
	}

	/** Every drop of both runs, each once, by name. */
	public static List<String> merged(List<String> first, Set<String> second) {
		Set<String> all = new HashSet<>(second);
		all.addAll(first);
		List<String> names = new ArrayList<>(all);
		names.sort(null);
		return names;
	}

	/** The counts of both runs added up, sorted by name. */
	public static Map<String, Integer> combined(Map<String, Integer> first, Map<String, Integer> second) {
		Map<String, Integer> total = new TreeMap<>(first);
		second.forEach((drop, count) -> total.merge(drop, count, Integer::sum));
		return total;
	}

	/** The stock with the drops' counts put over it. */
	public static Map<String, Integer> restocked(Map<String, Integer> stock, Map<String, Integer> drops) {
		Map<String, Integer> result = new HashMap<>(stock);
		result.putAll(drops);
		return result;
	}

	/** The drops of either run, each once. */
	public static Set<String> either(Set<String> first, Set<String> second) {
		Set<String> all = new HashSet<>();
		all.addAll(first);
		all.addAll(second);
		return all;
	}

	/** The names the stock has, then the labels of the drops, in one list. */
	public static List<String> named(Set<String> stock, Map<Integer, String> labels) {
		List<String> names = new ArrayList<>();
		names.addAll(stock);
		names.addAll(labels.values());
		return names;
	}

	/** The first and the last drop, such as "coins to bones". */
	public static String firstAndLast(List<String> drops) {
		return drops.getFirst() + " to " + drops.getLast();
	}
}
