package plugin;

/** A creature that fights, with a combat level. */
public class Monster extends Creature {

	public final int level;

	public Monster(String name, int level) {
		super(name);
		this.level = level;
	}
}
