package plugin.shapes;

/** A circle: a shape with a radius, named for its size. */
public class Circle extends Shape {

	private final double radius;

	public Circle(double radius) {
		String size = radius > 10 ? "large" : "small";
		super(size + " circle");
		this.radius = radius;
	}

	@Override
	public double area() {
		return Math.PI * radius * radius;
	}
}
