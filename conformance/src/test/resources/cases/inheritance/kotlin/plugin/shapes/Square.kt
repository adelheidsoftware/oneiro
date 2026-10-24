package plugin.shapes

/** A square: a shape with four equal sides. */
class Square(private val side: Double) : Shape("square") {

	override fun area(): Double {
		return side * side
	}

	override fun describe(): String {
		return "a ${super.describe()}"
	}
}
