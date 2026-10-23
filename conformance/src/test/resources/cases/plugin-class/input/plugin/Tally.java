package plugin;

/** A running total. */
public class Tally {

	private int total;

	public void add(int amount) {
		total += amount;
	}

	public int sum() {
		return total;
	}
}
