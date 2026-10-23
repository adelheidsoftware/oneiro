package plugin.shapes;

/** A shape with a name, which subclasses give an area. */
public abstract class Shape {

	protected final String name;

	protected Shape(String name) {
		this.name = name;
	}

	/** The area in square tiles. */
	public abstract double area();

	public String describe() {
		return name + " of " + area();
	}

	@Override
	public String toString() {
		return describe();
	}
}
