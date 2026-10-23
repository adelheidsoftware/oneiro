package plugin;

/** Constructs Lua cannot express, each rejected with its reason. */
public final class Rejected {

	private Rejected() {
	}

	public static int sum(int a, int b) {
		return a + b;
	}

	public static int sum(int a, int b, int c) {
		return a + b + c;
	}

	public static void spin() {
		synchronized (Rejected.class) {
		}
	}

	public static void check(boolean ready) {
		assert ready : "not ready";
	}

	public static int bump(int value) {
		int next = value++;
		return next;
	}

	public static int size(int count) {
		String[] names = new String[count];
		return count;
	}

	public static int größe() {
		return 0;
	}

	public static void search() {
		outer:
		for (int i = 0; i < 3; i++) {
			break outer;
		}
	}
}
