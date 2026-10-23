package plugin;

/** What to say about the creatures met. */
public final class Encounters {

	private Encounters() {
	}

	/** How dangerous a creature is, in a word. */
	public static String danger(Creature creature) {
		return switch (creature) {
			case Boss boss -> "deadly " + boss.name;
			case Monster monster when monster.level > 100 -> "strong " + monster.name;
			case Monster monster -> "weak " + monster.name;
			case Pet pet -> "friendly " + pet.name;
			default -> "unknown";
		};
	}

	/** The combat level of a creature that fights, or 0. */
	public static int level(Creature creature) {
		if (creature instanceof Monster monster) {
			return monster.level;
		}
		return 0;
	}

	/** Whether the value is text. */
	public static boolean isText(Object value) {
		return value instanceof String;
	}
}
