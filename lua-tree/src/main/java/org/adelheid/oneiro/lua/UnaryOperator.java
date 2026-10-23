package org.adelheid.oneiro.lua;

/** Lua's unary operators, which all share one precedence, between the multiplicative operators and {@code ^}. */
public enum UnaryOperator {

	NOT("not "),
	NEGATE("-"),
	LENGTH("#"),
	BIT_NOT("~");

	static final int PRECEDENCE = 11;

	private final String prefix;

	UnaryOperator(String prefix) {
		this.prefix = prefix;
	}

	/** The operator as written before its operand, with the space that {@code not} needs. */
	public String prefix() {
		return prefix;
	}
}
