package plugin

import java.util.function.Predicate
import org.adelheid.oneiro.rs3.Globals.log

/** Kotlin's own idioms: preconditions, fun interfaces, references to the library, literal maps and sets, labels. */
object Idioms {

	/** A test of a value, which a lambda or a class gives. */
	fun interface Test {
		fun passes(value: Int): Boolean
	}

	/** Steps of a size a private companion keeps, which only the class reaches. */
	class Steps {

		/** How far the steps go, from the start. */
		fun total(count: Int): Int = count * SIZE + start()

		private companion object {

			const val SIZE = 3

			fun start(): Int = 1
		}
	}

	/** A test a class gives, which Lua calls as the function every Test is. */
	class AtLeast(private val minimum: Int) : Test {
		override fun passes(value: Int): Boolean {
			return value >= minimum
		}
	}

	/** The values that pass the test. */
	fun passing(values: List<Int>, test: Test): List<Int> {
		return values.filter { test.passes(it) }
	}

	/** A test made from a lambda. */
	fun positive(): Test {
		return Test { it > 0 }
	}

	/** Whether the value passes a Predicate made from a lambda. */
	fun small(value: Int): Boolean {
		val predicate = Predicate<Int> { it < 10 }
		return predicate.test(value)
	}

	/** A level, which must be a real one. */
	fun level(value: Int): Int {
		require(value in 1..120) { "level $value" }
		check(value != 99)
		return value
	}

	/** The name of a herb, which the map must hold. */
	fun herbName(names: Map<Int, String>, id: Int): String {
		val name = requireNotNull(names[id]) { "no herb $id" }
		return name
	}

	/** The seed of a herb, which the map must hold. */
	fun seed(seeds: Map<String, Int>, herb: String): Int {
		requireNotNull(seeds[herb])
		return checkNotNull(seeds[herb])
	}

	/** Whether a setting is on, which must be known. */
	fun enabled(setting: Boolean?): Boolean {
		val on = setting ?: error("The setting is unknown.")
		return on
	}

	/** Work for later, a Runnable made from a lambda. */
	fun counter(counts: IntArray): Runnable {
		return Runnable { counts[0]++ }
	}

	/** The lines without their outer spaces, each printed. */
	fun trimmed(lines: List<String>): List<String> {
		val trimmed = lines.map(String::trim)
		trimmed.forEach(::println)
		return trimmed
	}

	/** Whether each name is one the set knows. */
	fun known(names: List<String>, set: Set<String>): List<Boolean> {
		return names.map(set::contains)
	}

	/** The levels of the herbs, in the order written. */
	fun levels(): Map<String, Int> {
		return mapOf("guam" to 3, "marrentill" to 5)
	}

	/** The herb patches, in the order written. */
	fun patches(): Set<String> {
		return setOf("falador", "catherby")
	}

	/** The prices, whose order does not matter. */
	fun prices(): Map<String, Int> {
		return hashMapOf("guam" to 10, "ranarr" to 7000)
	}

	/** The total of the values before the first negative one, which a labelled break leaves at. */
	fun beforeNegative(values: List<Int>): Int {
		var total = 0
		values@ for (value in values) {
			if (value < 0) {
				break@values
			}
			total += value
		}
		return total
	}

	/** A value known to be text, as text. */
	fun asText(value: Any): String = value as String

	/** A value as text when it is text, and null otherwise. */
	fun textOrNull(value: Any): String? = value as? String

	/** Logs a tick, with a lambda that never reads its parameter. */
	fun ticker(): (Int) -> Unit {
		return { log("tick") }
	}
}
