package plugin

/** A bank of tabs, each its own. */
class Bank(val owner: String) {

	/** A tab of the bank. */
	inner class Tab(val name: String)
}
