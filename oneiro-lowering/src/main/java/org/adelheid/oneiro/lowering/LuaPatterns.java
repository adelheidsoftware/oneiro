package org.adelheid.oneiro.lowering;

import static org.adelheid.oneiro.lua.Expression.binary;
import static org.adelheid.oneiro.lua.Expression.integer;
import static org.adelheid.oneiro.lua.Expression.methodCall;

import java.util.List;
import org.adelheid.oneiro.lua.BinaryOperator;
import org.adelheid.oneiro.lua.Expression;

/**
 * The Lua of {@code LuaString}, the plugin's way to Lua's string patterns where Java and Kotlin would use regular
 * expressions, which Lua does not have: each of its functions is Lua's string function of the same name. Both front
 * ends lower its calls here, and reject regular expressions with the errors that point to it.
 */
public final class LuaPatterns {

	/** The class a plugin calls. */
	public static final String CLASS = "org.adelheid.oneiro.api.LuaString";

	/** The error at a regular expression. */
	public static final String NO_REGEX = "Lua has no regular expressions; use LuaString.match, find, gsub or gmatch"
			+ " with a Lua pattern.";

	/** The error at {@code gmatch} outside a for loop. */
	public static final String GMATCH_OUTSIDE_LOOP = "LuaString.gmatch gives its matches one at a time to a for loop;"
			+ " loop over it, or read the first match's captures with LuaString.captures.";

	private LuaPatterns() {
	}

	/**
	 * A call of one of {@code LuaString}'s functions but {@code gmatch}, which a for loop takes apart.
	 *
	 * @param arguments the lowered arguments: the text, the pattern, and for {@code gsub} the replacement
	 */
	public static Expression call(String function, List<Expression> arguments) {
		Expression text = arguments.get(0);
		Expression pattern = arguments.get(1);
		return switch (function) {
			case "match" -> methodCall(text, "match", pattern);
			// Every value match returns, gathered into a list
			case "captures" -> new Expression.Table(List.of(
					new Expression.TableField.Positional(methodCall(text, "match", pattern))));
			// find gives the position counted from 1, or nil
			case "find" -> binary(binary(methodCall(text, "find", pattern), BinaryOperator.OR, integer(0)),
					BinaryOperator.SUBTRACT, integer(1));
			// gsub also gives the number of replacements, which the parentheses leave out
			case "gsub" -> new Expression.Parenthesized(methodCall(text, "gsub", pattern, arguments.get(2)));
			default -> throw new IllegalStateException("LuaString has no function " + function);
		};
	}

	/** The iterator a for loop over {@code LuaString.gmatch(text, pattern)} walks: {@code text:gmatch(pattern)}. */
	public static Expression matches(Expression text, Expression pattern) {
		return methodCall(text, "gmatch", pattern);
	}
}
