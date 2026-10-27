package plugin

/** Dev code that would read the order of a main enum whose constants are strings. */
object Order {

	fun place(style: SummaryStyle): Int = style.ordinal

	fun before(a: SummaryStyle, b: SummaryStyle): Boolean = a < b

	fun plain(): Any = Plain()
}
