package org.adelheid.oneiro.lowering;

import static org.adelheid.oneiro.lua.Expression.binary;
import static org.adelheid.oneiro.lua.Expression.integer;

import org.adelheid.oneiro.lua.BinaryOperator;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * Arithmetic on the Lua expression of an index, which Java counts from 0 and Lua from 1: adding or subtracting one,
 * folded where the result reads better.
 */
public final class IndexArithmetic {

	private IndexArithmetic() {
	}

	public static Expression plusOne(Expression expression) {
		return offset(expression, 1);
	}

	public static Expression minusOne(Expression expression) {
		return offset(expression, -1);
	}

	/**
	 * {@code expression + delta}, folded where the result reads better: {@code 0 + 1} is {@code 1},
	 * {@code n - 1 + 1} is {@code n}, and {@code n + 2 - 1} is {@code n + 1}.
	 */
	private static Expression offset(Expression expression, long delta) {
		Long literal = integerValue(expression);
		if (literal != null) {
			return integer(literal + delta);
		}
		if (expression instanceof Expression.Binary sum
				&& (sum.operator() == BinaryOperator.ADD || sum.operator() == BinaryOperator.SUBTRACT)) {
			Long amount = integerValue(sum.right());
			if (amount != null) {
				long combined = (sum.operator() == BinaryOperator.ADD ? amount : -amount) + delta;
				if (combined == 0) {
					return sum.left();
				}
				return combined > 0
						? binary(sum.left(), BinaryOperator.ADD, integer(combined))
						: binary(sum.left(), BinaryOperator.SUBTRACT, integer(-combined));
			}
		}
		return delta > 0
				? binary(expression, BinaryOperator.ADD, integer(delta))
				: binary(expression, BinaryOperator.SUBTRACT, integer(-delta));
	}

	/** The value of a decimal integer numeral, or of a negated one; null for anything else. */
	public static @Nullable Long integerValue(Expression expression) {
		if (expression instanceof Expression.NumberLiteral numeral && numeral.text().matches("\\d+")) {
			return Long.parseLong(numeral.text());
		}
		if (expression instanceof Expression.Unary negation && negation.operator() == UnaryOperator.NEGATE
				&& negation.operand() instanceof Expression.NumberLiteral numeral && numeral.text().matches("\\d+")) {
			return -Long.parseLong(numeral.text());
		}
		return null;
	}
}
