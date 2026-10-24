package plugin.shapes

import kotlin.math.PI

/** A circle: a shape with a radius, named for its size. */
class Circle(private val radius: Double) : Shape("${if (radius > 10) "large" else "small"} circle") {

	override fun area(): Double {
		return PI * radius * radius
	}
}
