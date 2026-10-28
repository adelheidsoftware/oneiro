package plugin

/** Annotations on expressions, which the Lua leaves out. */
object Annotated {

	/** The names in a value, with the annotation on the cast a local takes. */
	fun names(value: Any): List<String> {
		val names = @Suppress("UNCHECKED_CAST") (value as List<String>)
		return names
	}

	/** The names in a value, or none, with the annotation on the cast a try gives a local. */
	fun namesOrNone(value: Any): List<String> {
		val names = try {
			@Suppress("UNCHECKED_CAST") (value as List<String>)
		} catch (e: Exception) {
			listOf()
		}
		return names
	}

	/** Whether a level is high, with the annotation on the if a local takes. */
	fun high(level: Int): Boolean {
		val high = @Suppress("SimplifyBooleanWithConstants") if (level > 50) true else false
		return high
	}
}
