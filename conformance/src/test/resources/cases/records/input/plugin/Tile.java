package plugin;

/** A tile on the map, with the steps to another. */
public record Tile(int x, int y, int plane) {

	/** The tile at the origin of the ground plane. */
	public static final Tile ORIGIN = new Tile(0, 0, 0);

	public Tile {
		if (plane < 0 || plane > 3) {
			throw new IllegalArgumentException("plane " + plane);
		}
	}

	/** The tile at the coordinates on the ground plane. */
	public static Tile ground(int x, int y) {
		return new Tile(x, y, 0);
	}

	/** The number of steps to the other tile, moving diagonally. */
	public int stepsTo(Tile other) {
		return Math.max(Math.abs(x - other.x()), Math.abs(y - other.y));
	}

	@Override
	public String toString() {
		return x + ", " + y + ", " + plane;
	}
}
