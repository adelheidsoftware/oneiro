package plugin;

/** Things that can say what they are, with a shared way of saying it. */
public interface Describable {

	/** How a description starts. */
	String OPENING = "a";

	String label();

	/** The thing's label and what it is. */
	default String describe() {
		return OPENING + " " + label() + " " + kind();
	}

	/** What kind of thing it is; a thing unless it says otherwise. */
	default String kind() {
		return "thing";
	}

	/** The thing's description in capitals. */
	static String shout(Describable thing) {
		return thing.describe().toUpperCase();
	}
}
