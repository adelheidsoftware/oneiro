package plugin;

/** Interop features used where Lua cannot follow. */
public final class Misuse {

	/** A record with a method of its own. */
	public record Point(int x, int y) {
		public int sum() {
			return x + y;
		}
	}

	private Misuse() {
	}

	public static native int outside();

	public static String describe(Point point) {
		return "point " + point;
	}

	public static Object create() {
		return new Store();
	}

	public static int hashOf(Integer count) {
		return count.hashCode();
	}
}
