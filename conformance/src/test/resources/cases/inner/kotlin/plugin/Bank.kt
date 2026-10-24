package plugin

/** A bank whose tabs know the bank they belong to. */
class Bank(private val owner: String) {

	private val tabs = ArrayList<Tab>()

	// The first tab is made with the bank
	init {
		// Every bank starts with its first tab
		tabs.add(Tab("main")) // named as the client names it
		// More tabs open as the player adds them
	}

	/** Opens a new tab with the name. */
	fun open(name: String): Tab {
		val tab = Tab(name)
		tabs.add(tab)
		return tab
	}

	fun tabCount(): Int {
		return tabs.size
	}

	/** One tab of the bank, which reads the bank's owner. */
	inner class Tab(private val name: String) {

		/** The tab's title, with the owner's name. */
		fun title(): String {
			return "$owner's $name (${tabCount()} of ${this@Bank.tabs.size})"
		}
	}
}
