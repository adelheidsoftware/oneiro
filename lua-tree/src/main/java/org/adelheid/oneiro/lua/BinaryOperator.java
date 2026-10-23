package org.adelheid.oneiro.lua;

/**
 * Lua's binary operators with their precedence, from {@code or} (lowest) to {@code ^} (highest), as in section 3.4.8
 * of the Lua 5.4 manual. Unary operators sit at {@link UnaryOperator#PRECEDENCE}, between the multiplicative
 * operators and {@code ^}. The printer uses the precedence to add only the parentheses Lua needs.
 */
public enum BinaryOperator {

	OR("or", 1),
	AND("and", 2),
	LESS("<", 3),
	GREATER(">", 3),
	LESS_EQUAL("<=", 3),
	GREATER_EQUAL(">=", 3),
	NOT_EQUAL("~=", 3),
	EQUAL("==", 3),
	BIT_OR("|", 4),
	BIT_XOR("~", 5),
	BIT_AND("&", 6),
	SHIFT_LEFT("<<", 7),
	SHIFT_RIGHT(">>", 7),
	CONCAT("..", 8),
	ADD("+", 9),
	SUBTRACT("-", 9),
	MULTIPLY("*", 10),
	DIVIDE("/", 10),
	FLOOR_DIVIDE("//", 10),
	MODULO("%", 10),
	POWER("^", 12);

	private final String symbol;
	private final int precedence;

	BinaryOperator(String symbol, int precedence) {
		this.symbol = symbol;
		this.precedence = precedence;
	}

	public String symbol() {
		return symbol;
	}

	int precedence() {
		return precedence;
	}

	/** Whether a chain of operators at this precedence groups from the right, as {@code ..} and {@code ^} do. */
	boolean rightAssociative() {
		return this == CONCAT || this == POWER;
	}
}
