package plugin;

/** Something that lives on the map, with a name. */
public abstract class Creature {

	protected final String name;

	protected Creature(String name) {
		this.name = name;
	}
}
