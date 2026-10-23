package plugin;

/** A herb that says what it is, and orders by the level it needs. */
public class Herbal implements Describable, Comparable<Herbal> {

	private final String herb;
	private final int level;

	public Herbal(String herb, int level) {
		this.herb = herb;
		this.level = level;
	}

	@Override
	public String label() {
		return herb;
	}

	@Override
	public String kind() {
		return "herb";
	}

	@Override
	public int compareTo(Herbal other) {
		return level - other.level;
	}
}
