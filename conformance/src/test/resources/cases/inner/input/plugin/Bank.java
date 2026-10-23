package plugin;

import java.util.ArrayList;
import java.util.List;

/** A bank whose tabs know the bank they belong to. */
public class Bank {

	private final String owner;
	private final List<Tab> tabs = new ArrayList<>();

	{
		// Every bank starts with its first tab
		tabs.add(new Tab("main"));
	}

	public Bank(String owner) {
		this.owner = owner;
	}

	/** Opens a new tab with the name. */
	public Tab open(String name) {
		Tab tab = new Tab(name);
		tabs.add(tab);
		return tab;
	}

	public int tabCount() {
		return tabs.size();
	}

	/** One tab of the bank, which reads the bank's owner. */
	public class Tab {

		private final String name;

		public Tab(String name) {
			this.name = name;
		}

		/** The tab's title, with the owner's name. */
		public String title() {
			return owner + "'s " + name + " (" + tabCount() + " of " + Bank.this.tabs.size() + ")";
		}
	}
}
