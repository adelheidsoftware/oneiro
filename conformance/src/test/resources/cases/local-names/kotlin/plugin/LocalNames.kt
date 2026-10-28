package plugin

import org.adelheid.oneiro.rs3.Globals.log

/** The locals Oneiro names: each name once in a function, none a global's, a list's after where it goes. */
object LocalNames {

	/** Lines of text a section keeps. */
	class Sections {

		private var lines: List<String> = listOf()

		/** Keeps the names in capitals, a list built for a field. */
		fun keep(names: List<String>) {
			lines = names.map { name -> name.uppercase() }
		}
	}

	/** Shows a titled list of texts. */
	fun show(title: String, texts: List<String>) {
		log(title, texts.size)
	}

	/** How many groups have more than one big value: a count inside a count's lambda. */
	fun busyGroups(groups: List<List<Int>>): Int = groups.count { group -> group.count { value -> value > 1 } > 1 }

	/** The names shown twice: a list handed to a parameter, named after it, and then another. */
	fun showBoth(names: List<String>) {
		show("loud", names.map { name -> name.uppercase() })
		show("quiet", names.map { name -> name.lowercase() })
	}

	/** How many names are empty, and how many long, beside a local of its own named count. */
	fun counts(names: List<String>): String {
		val count = names.size
		return "$count: ${names.count { name -> name.isEmpty() }} empty, ${names.count { name -> name.length > 3 }} long"
	}

	/** A value's kind in words, logged, and whether it is text: a switch's local beside the log() it would hide. */
	fun describe(value: Any): String {
		Kinds.look(value)
		val described = when (Kinds.log()) {
			"number" -> "a number"
			"other" -> "something"
			else -> "unknown"
		}
		log(described)
		return if (value is String) described + ", text" else described
	}
}
