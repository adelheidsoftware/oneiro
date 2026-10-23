package plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A garden that plants, sorts and describes herbs. */
public final class Garden {

	private Garden() {
	}

	/** Plants the herbs one by one with a planter. */
	public static List<String> plant(List<String> herbs) {
		Planter planter = new Planter();
		herbs.forEach(planter);
		return planter.all();
	}

	/** The herbs from the lowest level up. */
	public static List<Herbal> byLevel(List<Herbal> herbs) {
		List<Herbal> sorted = new ArrayList<>(herbs);
		Collections.sort(sorted);
		return sorted;
	}

	/** A herb's description in capitals. */
	public static String shout(Herbal herb) {
		return Describable.shout(herb);
	}
}
