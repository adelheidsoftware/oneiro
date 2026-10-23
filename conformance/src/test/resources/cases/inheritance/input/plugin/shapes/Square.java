package plugin.shapes;

/** A square: a shape with four equal sides. */
public class Square extends Shape {

	private final double side;

	public Square(double side) {
		super("square");
		this.side = side;
	}

	@Override
	public double area() {
		return side * side;
	}

	@Override
	public String describe() {
		return "a " + super.describe();
	}
}
