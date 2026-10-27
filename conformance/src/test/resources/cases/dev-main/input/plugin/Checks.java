package plugin;

/** Dev code that uses the main source set's types as the main set's Lua makes them. */
public final class Checks {

	private Checks() {
	}

	/** How many summary styles there are. */
	public static int styles() {
		return SummaryStyle.values().length;
	}

	/** A style's name. */
	public static String styleName(SummaryStyle style) {
		return style.name();
	}

	/** Where the skills section comes. */
	public static int skillsOrder() {
		return Section.SKILLS.ordinal();
	}

	/** The skills section's name and title. */
	public static String skillsLabel() {
		return Section.SKILLS.name() + ": " + Section.SKILLS.title;
	}

	/** Whether the speed is the fast one, by its order. */
	public static boolean isFast(Speed speed) {
		return speed.ordinal() == 1;
	}

	/** A summary of one level. */
	public static Summary summary(int xp) {
		return new Summary(xp, 1);
	}

	/** A tally of three, doubled. */
	public static int doubledThree() {
		return new Tally(3).doubled();
	}

	/** Whether the value is a shape, a circle among them. */
	public static boolean isShape(Object value) {
		return value instanceof Shape;
	}

	/** A tab of the bank. */
	public static Bank.Tab tab(Bank bank, String name) {
		return bank.new Tab(name);
	}
}
