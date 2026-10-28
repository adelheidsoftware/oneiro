package org.adelheid.oneiro.lowering.translation;

import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.UnaryOperator;

/**
 * Which Lua values read again give what they gave the first time, as cheaply as a local does: a name, a literal, or a
 * field or index of those. Code that reads a value twice reads such a value as it is, and puts any other, such as a
 * call, in a local first.
 */
public final class PlainValues {

	private PlainValues() {
	}

	public static boolean isPlain(Expression value) {
		return switch (value) {
			case Expression.Name _, Expression.NumberLiteral _, Expression.StringLiteral _, Expression.BooleanLiteral _,
					Expression.Nil _ -> true;
			case Expression.Unary negation when negation.operator() == UnaryOperator.NEGATE ->
					negation.operand() instanceof Expression.NumberLiteral;
			case Expression.Field field -> isPlain(field.target());
			case Expression.Index index -> isPlain(index.target()) && isPlain(index.key());
			default -> false;
		};
	}
}
