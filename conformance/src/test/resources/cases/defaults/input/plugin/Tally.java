package plugin;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Counting, grouping and queueing with the collections' own methods. */
public final class Tally {

	private Tally() {
	}

	/** How often each word comes up. */
	public static Map<String, Integer> counts(List<String> words) {
		Map<String, Integer> counts = new HashMap<>();
		words.forEach(word -> counts.merge(word, 1, Integer::sum));
		return counts;
	}

	/** The words by their first letter. */
	public static Map<String, List<String>> byInitial(List<String> words) {
		Map<String, List<String>> groups = new HashMap<>();
		for (String word : words) {
			groups.computeIfAbsent(word.substring(0, 1), initial -> new ArrayList<>()).add(word);
		}
		return groups;
	}

	/** Drops the short words, and the empty ones from the set. */
	public static void prune(List<String> words, Set<String> seen) {
		words.removeIf(word -> word.length() < 3);
		seen.removeIf(word -> word.isEmpty());
	}

	/** Whether a feature is on: one nobody set is on. */
	public static boolean enabled(Map<String, Boolean> features, String name) {
		return features.getOrDefault(name, true);
	}

	/** The fields of a line of comma-separated values. */
	public static String[] fields(String line) {
		return line.split(",");
	}

	/** Puts a job at each end of the queue, and takes the one at the front. */
	public static String next(Deque<String> jobs) {
		jobs.addLast("later");
		jobs.addFirst("urgent");
		return jobs.poll();
	}

	/** The counts as Java writes a map. */
	public static String describe(Map<String, Integer> counts) {
		return "counts: " + counts;
	}

	/** Notes the tick a name was first seen at. */
	public static void remember(Map<String, Integer> firstSeen, String name, int tick) {
		firstSeen.putIfAbsent(name, tick);
	}

	/** The word reversed and bracketed, built up in place. */
	public static String reversed(String word) {
		StringBuilder text = new StringBuilder(word);
		text.reverse();
		text.insert(0, "[");
		text.append("]");
		return text.toString();
	}
}
