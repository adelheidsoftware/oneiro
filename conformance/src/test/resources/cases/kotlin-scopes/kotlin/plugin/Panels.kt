package plugin

import org.adelheid.oneiro.rs3.Globals.log
import org.adelheid.oneiro.rs3.ui

/** Kotlin's scope functions, opened where they are called: let, also, apply, run and with. */
object Panels {

	/** A running total. */
	class Tally(var total: Int) {

		/** Adds the amount to the total. */
		fun add(amount: Int) {
			total += amount
		}
	}

	/** A label under the parent, placed and filled in. */
	fun label(parent: ui.ComponentCollection, words: String): ui.Text {
		return ui.Text(parent).apply {
			content = words
			SetPos(10, 20)
		}
	}

	/** A label under the parent, kept in a local, then shown. */
	fun shown(parent: ui.ComponentCollection, words: String): ui.Text {
		val label = ui.Text(parent).apply {
			// Where the label goes
			SetPos(10, 20)
			content = words
		}
		label.hidden = false
		return label
	}

	/** Hides a label and moves it out of the way. */
	fun hide(label: ui.Text) {
		label.apply {
			hidden = true
			SetPos(0, 0)
		}
	}

	/** The name in capitals. */
	fun shout(name: String): String {
		val loud = name.let { it.uppercase() }
		return loud
	}

	/** The length of the name without its outer spaces, or 0 for no name. */
	fun trimmedLength(name: String?): Int {
		return name?.let { it.trim().length } ?: 0
	}

	/** A new tally of the amount. */
	fun tallied(amount: Int): Tally {
		return Tally(0).also { tally -> tally.add(amount) }
	}

	/** Logs the tally's total, and gives the tally on. */
	fun logged(tally: Tally): Tally {
		return tally.also { log("total ${it.total}") }
	}

	/** The total after adding both amounts to the tally. */
	fun total(tally: Tally, first: Int, second: Int): Int {
		return tally.run {
			add(first)
			add(second)
			total
		}
	}

	/** The total of a new tally of the amount, after adding one. */
	fun fresh(amount: Int): Int {
		val total = Tally(amount).run {
			add(1)
			total
		}
		return total
	}

	/** A line about the tally. */
	fun describe(tally: Tally): String {
		return with(tally) { "total ${this.total}" }
	}

	/** Logs each name of more than three letters, in capitals. */
	fun logLong(names: List<String>) {
		for (name in names) {
			if (name.length > 3) {
				name.let { log(it.uppercase()) }
			}
		}
	}

	/** A line about the name's length. */
	fun nameLength(name: String): String {
		return "length " + name.let { it.length }
	}

	/** Logs what a tally of three comes to after adding two. */
	fun demo() {
		Tally(3).apply {
			add(2)
			log("total $total")
		}
	}
}
