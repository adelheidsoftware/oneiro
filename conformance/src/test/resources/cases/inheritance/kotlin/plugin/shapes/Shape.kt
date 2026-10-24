package plugin.shapes

/** A shape with a name, which subclasses give an area. */
abstract class Shape(val name: String) {

	/** The area in square tiles. */
	abstract fun area(): Double

	open fun describe(): String {
		return "$name of ${area()}"
	}

	override fun toString(): String {
		return describe()
	}
}
