package plugin;

import java.util.Comparator;
import java.util.List;

/** Streams with more steps: limit and skip, a sort with steps after it, and lambdas of several statements. */
public final class Rankings {

	private Rankings() {
	}

	/** A drop: what it was, how many, and what one is worth. */
	public record Drop(String item, int count, int price) {
	}

	/** The first few items. */
	public static List<String> firstItems(List<Drop> drops, int count) {
		return drops.stream().map(drop -> drop.item()).limit(count).toList();
	}

	/** The items of the first half of the drops, a limit worked out once. */
	public static List<String> firstHalf(List<Drop> drops) {
		return drops.stream().limit(drops.size() / 2).map(drop -> drop.item()).toList();
	}

	/** The items after the first. */
	public static List<String> laterItems(List<Drop> drops) {
		return drops.stream().skip(1).map(drop -> drop.item()).toList();
	}

	/** The two most valuable drops: the drops sorted, then the first two taken. */
	public static List<Drop> mostValuable(List<Drop> drops) {
		return drops.stream().sorted(Comparator.comparingInt(Drop::price).reversed()).limit(2).toList();
	}

	/** The items of the stacks, from the smallest up. */
	public static List<String> stackItems(List<Drop> drops) {
		return drops.stream().filter(drop -> drop.count() > 1).sorted(Comparator.comparingInt(Drop::count))
				.map(drop -> drop.item()).toList();
	}

	/** The items of the drops worth keeping, by a test in steps. */
	public static List<String> keepers(List<Drop> drops) {
		return drops.stream().filter(drop -> {
			int worth = drop.count() * drop.price();
			return worth > 150;
		}).map(drop -> drop.item()).toList();
	}

	/** Each drop's line, worked out in steps. */
	public static List<String> lines(List<Drop> drops) {
		return drops.stream().map(drop -> {
			String count = drop.count() + "x";
			return count + " " + drop.item();
		}).toList();
	}

	/** How many drops of each group are single, a stream inside a stream's lambda. */
	public static List<Integer> singles(List<List<Drop>> groups) {
		return groups.stream().map(group -> (int) group.stream().filter(drop -> drop.count() == 1).count()).toList();
	}
}
