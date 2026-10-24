package org.adelheid.oneiro.java;

import static org.adelheid.oneiro.lua.Expression.binary;
import static org.adelheid.oneiro.lua.Expression.call;
import static org.adelheid.oneiro.lua.Expression.field;
import static org.adelheid.oneiro.lua.Expression.integer;
import static org.adelheid.oneiro.lua.Expression.methodCall;
import static org.adelheid.oneiro.lua.Expression.name;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodInvocationTree;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeKind;
import org.adelheid.oneiro.lowering.Helper;
import org.adelheid.oneiro.lowering.IndexArithmetic;
import org.adelheid.oneiro.lua.BinaryOperator;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.UnaryOperator;

/**
 * The methods of {@code String}, and its static ones, as Lua's string functions and operators: {@code #s},
 * {@code s:sub}, plain {@code s:find}, {@code s:upper}, {@code ..} and the rest. A char is a one-character
 * string, an index counts from 1, and a method that takes a regular expression is rejected unless the
 * expression only matches itself.
 */
final class StringMethods {

	private final LibraryCalls library;
	private final BodyLowering body;
	private final UnitLowering unit;
	private final JavaTypes types;

	StringMethods(LibraryCalls library) {
		this.library = library;
		this.body = library.body;
		this.unit = body.unit;
		this.types = body.types();
	}

	Expression stringMethod(MethodInvocationTree call, String name, Expression text,
			List<? extends ExpressionTree> arguments) {
		return switch (name) {
			case "length" -> LibraryCalls.length(text);
			case "isEmpty" -> binary(text, BinaryOperator.EQUAL, Expression.string(""));
			case "equals" -> binary(text, BinaryOperator.EQUAL, body.expression(arguments.getFirst()));
			case "equalsIgnoreCase" -> binary(methodCall(text, "lower"), BinaryOperator.EQUAL,
					methodCall(body.expression(arguments.getFirst()), "lower"));
			case "substring" -> arguments.size() == 1
					? methodCall(text, "sub", IndexArithmetic.plusOne(body.expression(arguments.getFirst())))
					: methodCall(text, "sub", IndexArithmetic.plusOne(body.expression(arguments.getFirst())),
							body.expression(arguments.get(1)));
			case "contains" -> {
				Expression found = methodCall(text, "find", body.expression(arguments.getFirst()), integer(1),
						new Expression.BooleanLiteral(true));
				yield binary(found, BinaryOperator.NOT_EQUAL, new Expression.Nil());
			}
			case "indexOf" -> {
				ExpressionTree sought = arguments.getFirst();
				if (arguments.size() != 1) {
					throw Rejection.notYet(call, "String.indexOf with a start position");
				}
				unit.use(Helper.STRING_INDEX_OF);
				Expression soughtText;
				if (types.isString(unit.type(sought))) {
					soughtText = body.expression(sought);
				} else if (isCharType(sought)) {
					// indexOf(int) takes a char as its number, but a char is the one-character string to find
					soughtText = body.asWritten(sought);
				} else {
					soughtText = characterOf(sought);
				}
				yield Helper.STRING_INDEX_OF.invoke(text, soughtText);
			}
			case "startsWith" -> {
				Expression prefix = body.expression(arguments.getFirst());
				yield binary(methodCall(text, "sub", integer(1), byteLength(arguments.getFirst(), prefix)),
						BinaryOperator.EQUAL, prefix);
			}
			case "endsWith" -> {
				Expression suffix = body.expression(arguments.getFirst());
				if (arguments.getFirst() instanceof LiteralTree literal && literal.getValue() instanceof String value
						&& !value.isEmpty()) {
					Expression length = integer(value.getBytes(StandardCharsets.UTF_8).length);
					Expression end = methodCall(text, "sub", new Expression.Unary(UnaryOperator.NEGATE, length));
					yield binary(end, BinaryOperator.EQUAL, suffix);
				}
				unit.use(Helper.ENDS_WITH);
				yield Helper.ENDS_WITH.invoke(text, suffix);
			}
			case "toUpperCase" -> noLocale(call, methodCall(text, "upper"));
			case "toLowerCase" -> noLocale(call, methodCall(text, "lower"));
			case "trim", "strip" -> {
				unit.use(Helper.TRIM);
				yield Helper.TRIM.invoke(text);
			}
			case "repeat" -> methodCall(text, "rep", body.expression(arguments.getFirst()));
			case "toString" -> text;
			case "split" -> {
				String separator = LibraryCalls.plainSeparator(arguments.getFirst());
				if (separator == null) {
					throw new Rejection(call, "Lua has no regular expressions, so split takes only a separator that"
							+ " stands for itself, such as a comma or an escaped bar; for a pattern, use"
							+ " LuaString.gmatch.");
				}
				unit.use(Helper.SPLIT);
				yield arguments.size() == 1
						? Helper.SPLIT.invoke(text, Expression.string(separator))
						: Helper.SPLIT.invoke(text, Expression.string(separator), body.expression(arguments.get(1)));
			}
			case "matches", "replaceAll", "replaceFirst" -> throw new Rejection(call,
					"Lua has no regular expressions; use LuaString.match, find, gsub or gmatch with a Lua pattern.");
			case "charAt" -> characterAt(text, arguments.getFirst());
			case "toCharArray" -> {
				unit.use(Helper.CHARACTERS);
				yield Helper.CHARACTERS.invoke(text);
			}
			case "chars", "codePoints" -> throw new Rejection(call, LibraryCalls.NO_CHAR_STREAM);
			case "codePointAt" -> throw new Rejection(call, "A Lua string holds bytes, not code points;"
					+ " (int) text.charAt(i) is the byte's number, which is the code point for ASCII text.");
			default -> throw Rejection.notYet(call, "String." + name);
		};
	}

	/**
	 * The character at a Java index, as a one-character string: {@code text:sub(i + 1, i + 1)}. The index appears
	 * twice, so its Lua may not call anything; {@code s.length() - 1} is {@code #s}, which is no call.
	 */
	Expression characterAt(Expression text, ExpressionTree position) {
		Expression index = body.operators().index(position);
		if (callsAnything(index)) {
			throw new Rejection(position, "Lua would evaluate this index twice; store it in a local first.");
		}
		return methodCall(text, "sub", index, index);
	}

	/** Whether Lua calls a function to evaluate the expression, so that evaluating it twice could differ. */
	private static boolean callsAnything(Expression expression) {
		return switch (expression) {
			case Expression.Call _, Expression.MethodCall _ -> true;
			case Expression.Index index -> callsAnything(index.target()) || callsAnything(index.key());
			case Expression.Field field -> callsAnything(field.target());
			case Expression.Binary binary -> callsAnything(binary.left()) || callsAnything(binary.right());
			case Expression.Unary unary -> callsAnything(unary.operand());
			case Expression.Parenthesized parenthesized -> callsAnything(parenthesized.inner());
			default -> false;
		};
	}

	/** A value Java takes as a char: a char as the string it is, a number as the character it stands for. */
	Expression characterOf(ExpressionTree value) {
		if (isCharType(value)) {
			return body.expression(value);
		}
		return call(field(name("string"), "char"), body.expression(value));
	}

	boolean isCharType(ExpressionTree value) {
		return types.isChar(unit.type(value));
	}

	boolean isCharArray(ExpressionTree value) {
		return unit.type(value) instanceof ArrayType array && types.isChar(array.getComponentType());
	}

	private static Expression noLocale(MethodInvocationTree call, Expression lowered) {
		if (!call.getArguments().isEmpty()) {
			throw Rejection.notYet(call, "Changing case with a Locale");
		}
		return lowered;
	}

	/** {@code #text}, or the byte count itself when the text is a literal. */
	private static Expression byteLength(ExpressionTree tree, Expression lowered) {
		if (tree instanceof LiteralTree literal && literal.getValue() instanceof String value) {
			return integer(value.getBytes(StandardCharsets.UTF_8).length);
		}
		return LibraryCalls.length(lowered);
	}

	Expression stringStatic(MethodInvocationTree call, String name, List<? extends ExpressionTree> arguments) {
		return switch (name) {
			case "valueOf", "copyValueOf" -> isCharArray(arguments.getFirst())
					? call(field(name("table"), "concat"), body.expression(arguments.getFirst()))
					: body.strings().toText(arguments.getFirst());
			case "format" -> body.strings().format(call);
			case "join" -> {
				// String.join(", ", names) joins a list or array; String.join(", ", a, b) joins the values themselves
				ExpressionTree elements = arguments.get(1);
				boolean joinsCollection = arguments.size() == 2
						&& (types.isList(unit.type(elements)) || unit.type(elements).getKind() == TypeKind.ARRAY);
				Expression list = joinsCollection
						? body.expression(elements)
						: library.positional(arguments.subList(1, arguments.size()));
				yield call(field(name("table"), "concat"), list, body.expression(arguments.getFirst()));
			}
			default -> throw Rejection.notYet(call, "String." + name);
		};
	}
}
