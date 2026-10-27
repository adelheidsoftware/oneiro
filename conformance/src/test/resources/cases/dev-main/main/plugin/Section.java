package plugin;

/** A section of the panel, with its title: an enum whose constants are objects. */
public enum Section {
	SESSION("Session"), SKILLS("Skills");

	public final String title;

	Section(String title) {
		this.title = title;
	}
}
