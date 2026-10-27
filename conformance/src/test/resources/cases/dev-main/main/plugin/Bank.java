package plugin;

/** A bank of tabs, each its own. */
public class Bank {

	public final String owner;

	public Bank(String owner) {
		this.owner = owner;
	}

	/** A tab of the bank. */
	public class Tab {

		public final String name;

		public Tab(String name) {
			this.name = name;
		}
	}
}
