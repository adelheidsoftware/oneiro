package plugin;

/** Integer division on values that can never be negative, and on values that can. */
public class Counters {

	private int ticks;
	private int balance;
	public int shared;

	public void tick() {
		ticks++;
	}

	public void spend(int amount) {
		balance -= amount;
	}

	/** Whole minutes of ticks, at 100 a minute. */
	public int minutes() {
		return ticks / 100;
	}

	public String report() {
		int minutes = minutes();
		int hours = minutes / 60;
		return hours + "h " + minutes % 60 + "m";
	}

	public int halves(int[] values) {
		int total = 0;
		for (int value : values) {
			total += Math.abs(value);
		}
		return total / 2;
	}

	public int balanceTens() {
		return balance / 10;
	}

	public int sharedTens() {
		return shared / 10;
	}

	public int level() {
		return ticks / 1000;
	}

	public int levelHalves() {
		return level() / 2;
	}
}
