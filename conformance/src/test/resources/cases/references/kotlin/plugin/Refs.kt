package plugin

/** Function references as functions. */
object Refs {

	/** A seed, with its name. */
	data class Seed(val name: String)

	/** A sack that holds what it is given. */
	class Sack {

		private val held = ArrayList<String>()

		fun put(item: String) {
			held.add(item)
		}

		fun size(): Int {
			return held.size
		}
	}

	/** Each name's length, by a library property. */
	fun lengths(names: List<String>): List<Int> {
		return names.map(String::length)
	}

	/** The names that are not empty. */
	fun filled(names: List<String?>): Int {
		return names.filterNotNull().count(String::isNotEmpty)
	}

	/** The seeds of the names. */
	fun seeds(names: List<String>): List<Seed> {
		return names.map(::Seed)
	}

	/** The seeds' names, by the data class's property. */
	fun names(seeds: List<Seed>): List<String> {
		return seeds.map(Seed::name)
	}

	/** Puts every item into the sack, and counts them. */
	fun fill(items: List<String>): Int {
		val sack = Sack()
		items.forEach(sack::put)
		return sack.size()
	}

	/** The numbers in the texts. */
	fun numbers(texts: List<String>): List<Int> {
		return texts.map(String::toInt)
	}

	/** Copies the items into a list, through the list's add. */
	fun copied(items: List<String>): List<String> {
		val copy = ArrayList<String>()
		items.forEach(copy::add)
		return copy
	}

	/** Prints each line. */
	fun print(lines: List<String>) {
		lines.forEach(::println)
	}

	/** The texts in upper case. */
	fun shouted(texts: List<String>): List<String> {
		return texts.map(String::uppercase)
	}
}
