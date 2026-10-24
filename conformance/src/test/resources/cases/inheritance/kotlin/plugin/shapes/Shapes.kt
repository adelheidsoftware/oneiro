package plugin.shapes

/** Shapes measured through interfaces, and a subclass nested next to the code that uses it. */
object Shapes {

	/** Something with an area, which classes implement: its values are objects. */
	interface Measured {
		fun area(): Double
	}

	/** Turns a number into a number: its values are plain functions. */
	fun interface Scale {
		fun apply(value: Double): Double
	}

	/** A rectangle. */
	class Rectangle(private val width: Double, private val height: Double) : Shape("rectangle"), Measured {

		override fun area(): Double {
			return width * height
		}
	}

	fun total(shapes: List<Measured>): Double {
		var total = 0.0
		for (shape in shapes) {
			total += shape.area()
		}
		return total
	}

	fun scaled(shape: Shape, scale: Scale): Double {
		return scale.apply(shape.area())
	}

	fun doubled(shape: Shape): Double {
		return scaled(shape) { value -> value * 2 }
	}

	fun text(shape: Shape): String {
		return "shape: $shape"
	}
}
