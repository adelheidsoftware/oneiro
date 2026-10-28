package plugin

import org.adelheid.oneiro.rs3.Globals.log

/** The names of the locals Oneiro makes up: each once in a function, and a list's from where it goes. */
object LocalNames {

	/** Shows a titled list of texts. */
	fun show(title: String, texts: List<String>) {
		log(title, texts.size)
	}

	/** How many groups have more than one big value: a count inside a count's lambda. */
	fun busyGroups(groups: List<List<Int>>): Int = groups.count { group -> group.count { it > 1 } > 1 }

	/** The names shown twice: a list handed to a parameter, named after it, and then another. */
	fun showBoth(names: List<String>) {
		show("loud", names.map { it.uppercase() })
		show("quiet", names.map { it.lowercase() })
	}

	/** How many names are empty, and how many long, beside a local of its own named count. */
	fun counts(names: List<String>): String {
		val count = names.size
		return "$count: ${names.count { it.isEmpty() }} empty, ${names.count { it.length > 3 }} long"
	}

	/** The first three names. */
	fun top(names: List<String>): List<String> = names.take(3)

	/** The first names in rows of two, from a list a call gives, which a local takes before the loop. */
	fun rows(names: List<String>) {
		show("rows", top(names).chunked(2) { it.joinToString(" ") })
	}

	/** Lines of text a section keeps, in its properties. */
	class Sections {

		var lines: List<String> = listOf()

		val rows = mutableListOf(listOf<String>())

		/** Keeps the section's name as its lines, a list built for a property. */
		fun keep(section: String, count: Int) {
			lines = buildList {
				repeat(count) { add(section) }
			}
		}

		/** Makes the section's name the first row, a list built for an element of a property. */
		fun first(section: String) {
			rows[0] = buildList { add(section) }
		}
	}
}
