package plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The herbs planted in a farmer's patches. */
public final class Patches {

	private Patches() {
	}

	/** The herbs a farmer of the level can plant, as text. */
	public static String plantable(int farming) {
		StringBuilder text = new StringBuilder();
		for (Herb herb : Herb.values()) {
			if (herb.canPlant(farming)) {
				text.append(herb).append(' ');
			}
		}
		return text.toString().trim();
	}

	/** How many patches grow each herb. */
	public static Map<Herb, Integer> counts(List<Herb> planted) {
		Map<Herb, Integer> counts = new HashMap<>();
		for (Herb herb : planted) {
			counts.merge(herb, 1, Integer::sum);
		}
		return counts;
	}

	/** The herb a saved name stands for. */
	public static Herb saved(String name) {
		return Herb.valueOf(name);
	}

	/** Whether the first herb comes before the second. */
	public static boolean earlier(Herb first, Herb second) {
		return first.compareTo(second) < 0;
	}

	/** The stage's place in the growth, from 0. */
	public static int step(Stage stage) {
		return stage.ordinal();
	}

	/** The tiers from the easiest up, in the order the enum declares them. */
	public static List<Tier> easiestFirst(List<Tier> tiers) {
		List<Tier> sorted = new ArrayList<>(tiers);
		Collections.sort(sorted);
		return sorted;
	}

	/** What a herb's patch looks like at the stage. */
	public static String look(Herb herb, Stage stage) {
		return switch (stage) {
			case SEED -> "a seed of " + herb.describe();
			case GROWING -> "growing " + herb.name();
			case DONE -> herb + " ready";
		};
	}
}
