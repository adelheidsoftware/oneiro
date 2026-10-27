package plugin;

/** A count that can double: a class, for its method. */
public record Tally(int count) {

	/** Twice the count. */
	public int doubled() {
		return count * 2;
	}
}
