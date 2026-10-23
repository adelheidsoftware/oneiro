package plugin.shapes;

import java.util.List;

/** Shapes measured through interfaces, and a subclass nested next to the code that uses it. */
public final class Shapes {

	/** Something with an area, which classes implement: its values are objects. */
	public interface Measured {
		double area();
	}

	/** Turns a number into a number: its values are plain functions. */
	public interface Scale {
		double apply(double value);
	}

	/** A rectangle. */
	public static class Rectangle extends Shape implements Measured {

		private final double width;
		private final double height;

		public Rectangle(double width, double height) {
			super("rectangle");
			this.width = width;
			this.height = height;
		}

		@Override
		public double area() {
			return width * height;
		}
	}

	private Shapes() {
	}

	public static double total(List<Measured> shapes) {
		double total = 0;
		for (Measured shape : shapes) {
			total += shape.area();
		}
		return total;
	}

	public static double scaled(Shape shape, Scale scale) {
		return scale.apply(shape.area());
	}

	public static double doubled(Shape shape) {
		return scaled(shape, value -> value * 2);
	}

	public static String text(Shape shape) {
		return "shape: " + shape;
	}
}
