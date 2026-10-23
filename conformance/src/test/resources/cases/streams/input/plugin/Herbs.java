package plugin;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Herb runs, summed up with streams. */
public final class Herbs {

	private Herbs() {
	}

	/** A patch's herb and how many it gave. */
	public record Harvest(String herb, int count) {
	}

	/** The herbs of the harvests that gave at least the minimum, in upper case. */
	public static List<String> good(List<Harvest> harvests, int minimum) {
		List<String> herbs = harvests.stream()
				.filter(harvest -> harvest.count() >= minimum)
				.map(harvest -> harvest.herb().toUpperCase())
				.toList();
		return herbs;
	}

	/** How many harvests gave nothing. */
	public static long empty(List<Harvest> harvests) {
		return harvests.stream().filter(harvest -> harvest.count() == 0).count();
	}

	/** Whether any harvest was of the herb. */
	public static boolean grew(List<Harvest> harvests, String herb) {
		return harvests.stream().anyMatch(harvest -> harvest.herb().equals(herb));
	}

	/** Whether every harvest gave something. */
	public static boolean allGave(List<Harvest> harvests) {
		boolean gave = harvests.stream().allMatch(harvest -> harvest.count() > 0);
		return gave;
	}

	/** The first herb with the prefix, or "none". */
	public static String firstWith(List<String> herbs, String prefix) {
		return herbs.stream().filter(herb -> herb.startsWith(prefix)).findFirst().orElse("none");
	}

	/** The herbs picked in all. */
	public static int total(List<Harvest> harvests) {
		return harvests.stream().mapToInt(harvest -> harvest.count()).sum();
	}

	/** The biggest harvest's count, or 0 when there were none. */
	public static int best(List<Harvest> harvests) {
		return harvests.stream().mapToInt(harvest -> harvest.count()).max().orElse(0);
	}

	/** The herbs as one line in lower case, such as "guam, marrentill". */
	public static String line(List<String> herbs) {
		return herbs.stream().map(herb -> herb.toLowerCase()).collect(Collectors.joining(", "));
	}

	/** Counts down from the number, into the list. */
	public static void countdown(int from, List<Integer> numbers) {
		IntStream.rangeClosed(1, from).forEach(i -> numbers.add(from - i + 1));
	}

	/** The harvests from the most to the least picked. */
	public static List<Harvest> ranked(List<Harvest> harvests) {
		return harvests.stream()
				.sorted(Comparator.comparingInt((Harvest harvest) -> harvest.count()).reversed())
				.toList();
	}

	/** The herbs the stock has any of. */
	public static Set<String> stocked(Map<String, Integer> stock) {
		Set<String> herbs = stock.keySet().stream().filter(herb -> stock.get(herb) > 0).collect(Collectors.toSet());
		return herbs;
	}

	/** Sorts the harvests by herb name, last first. */
	public static void byName(List<Harvest> harvests) {
		harvests.sort(Comparator.comparing((Harvest harvest) -> harvest.herb()).reversed());
	}

	/** Sorts the harvests by herb, then by count. */
	public static void byHerbThenCount(List<Harvest> harvests) {
		harvests.sort(Comparator.comparing((Harvest harvest) -> harvest.herb())
				.thenComparingInt(harvest -> harvest.count()));
	}

	/** Whether a herb's name is long. */
	private static boolean isLong(String herb) {
		return herb.length() > 6;
	}

	/** The herbs with long names. */
	public static List<String> longNames(List<String> herbs) {
		return herbs.stream().filter(Herbs::isLong).toList();
	}
}
