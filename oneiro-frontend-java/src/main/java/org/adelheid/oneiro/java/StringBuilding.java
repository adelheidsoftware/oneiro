package org.adelheid.oneiro.java;

import static org.adelheid.oneiro.lua.Expression.binary;
import static org.adelheid.oneiro.lua.Expression.call;
import static org.adelheid.oneiro.lua.Expression.field;
import static org.adelheid.oneiro.lua.Expression.name;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.adelheid.oneiro.lua.BinaryOperator;
import org.adelheid.oneiro.lua.Expression;
import org.jspecify.annotations.Nullable;

/**
 * Java's string building in the house style: {@code +} concatenation and {@code String.format} become one call of the
 * client's {@code formatStr}, and values become text by their Java type, as docs/java-subset.md lays out. The client
 * hands every number to {@code formatStr} as a double, so a {@code long} goes through {@code tostring} to stay exact,
 * and integer options such as {@code %x} are formatted by Lua's {@code string.format} inside the template.
 */
final class StringBuilding {

	private static final Pattern SPECIFIER = Pattern.compile("%(\\d+\\$)?([-#+ 0,(<]*)(\\d+)?(\\.\\d+)?([a-zA-Z%])");

	private final BodyLowering body;

	StringBuilding(BodyLowering body) {
		this.body = body;
	}

	private JavaTypes types() {
		return body.types();
	}

	private TypeMirror type(ExpressionTree tree) {
		return body.unit.type(tree);
	}

	/**
	 * A {@code +} concatenation: {@code formatStr("text {}", value)} when it has literal text or a value that is not a
	 * string, and {@code a .. b} when it joins only strings, a char being a one-character string.
	 */
	Expression concatenation(BinaryTree concatenation) {
		List<ExpressionTree> parts = new ArrayList<>();
		flatten(concatenation, parts);
		boolean onlyStringValues = parts.stream()
				.allMatch(part -> !(part instanceof LiteralTree)
						&& (types().isString(type(part)) || types().isChar(type(part))));
		if (onlyStringValues) {
			Expression joined = body.expression(parts.getLast());
			for (int index = parts.size() - 2; index >= 0; index--) {
				joined = binary(body.expression(parts.get(index)), BinaryOperator.CONCAT, joined);
			}
			return joined;
		}
		StringBuilder template = new StringBuilder();
		StringBuilder plainText = new StringBuilder();
		List<Expression> arguments = new ArrayList<>();
		for (ExpressionTree part : parts) {
			String text = literalText(part);
			if (text != null) {
				template.append(escapeBraces(text));
				plainText.append(text);
			} else {
				template.append("{}");
				arguments.add(formatArgument(part));
			}
		}
		if (arguments.isEmpty()) {
			return Expression.string(plainText.toString());
		}
		if (template.toString().equals("{}")) {
			// "" + value, Java's idiom for the value as text
			ExpressionTree value = parts.stream()
					.filter(part -> literalText(part) == null)
					.findFirst()
					.orElseThrow();
			return toText(value);
		}
		List<Expression> callArguments = new ArrayList<>();
		callArguments.add(Expression.string(template.toString()));
		callArguments.addAll(arguments);
		return new Expression.Call(name("formatStr"), callArguments);
	}

	/** The text of a string or char literal, which goes into the template as it is; null for anything else. */
	private static @Nullable String literalText(ExpressionTree part) {
		return part instanceof LiteralTree literal
				&& (literal.getValue() instanceof String || literal.getValue() instanceof Character)
				? literal.getValue().toString()
				: null;
	}

	/** The parts of {@code a + b + c}, following only the {@code +} that join strings, in order. */
	private void flatten(ExpressionTree tree, List<ExpressionTree> parts) {
		if (tree instanceof ParenthesizedTree parenthesized && isConcatenation(parenthesized.getExpression())) {
			flatten(parenthesized.getExpression(), parts);
		} else if (tree instanceof BinaryTree binary && isConcatenation(binary)) {
			flatten(binary.getLeftOperand(), parts);
			flatten(binary.getRightOperand(), parts);
		} else {
			parts.add(tree);
		}
	}

	private boolean isConcatenation(ExpressionTree tree) {
		return tree instanceof BinaryTree binary && binary.getKind() == Tree.Kind.PLUS
				&& types().isString(type(binary));
	}

	/** The value appended by {@code s += value}, as the right operand of {@code ..}: a string. */
	Expression concatOperand(ExpressionTree value) {
		return types().isString(type(value)) ? body.expression(value) : toText(value);
	}

	/**
	 * A value inside {@code formatStr}: strings, numbers, booleans and nil as they are; a {@code long} through
	 * {@code tostring}, because the client would round it to a double; plugin objects by their {@code toString()}.
	 */
	private Expression formatArgument(ExpressionTree value) {
		TypeMirror type = type(value);
		if (types().isLong(type)) {
			return call(name("tostring"), body.expression(value));
		}
		if (types().isString(type) || types().isIntegral(type) || types().isFloating(type) || types().isBoolean(type)
				|| type.getKind() == TypeKind.NULL) {
			return body.expression(value);
		}
		return objectText(value, type);
	}

	/**
	 * A value for {@code table.concat}, {@code print} or {@code error}, which take strings and numbers: integers as
	 * they are, doubles through {@code formatStr} so that they print as Java prints them, booleans and nil through
	 * {@code tostring}.
	 */
	Expression asText(ExpressionTree value) {
		TypeMirror type = type(value);
		if (types().isString(type) || types().isIntegral(type)) {
			return body.expression(value);
		}
		if (types().isFloating(type)) {
			return call(name("formatStr"), Expression.string("{}"), body.expression(value));
		}
		if (types().isBoolean(type) || type.getKind() == TypeKind.NULL) {
			return call(name("tostring"), body.expression(value));
		}
		return objectText(value, type);
	}

	/** A value turned into a string, as {@code String.valueOf} does. */
	Expression toText(ExpressionTree value) {
		TypeMirror type = type(value);
		if (types().isString(type)) {
			return body.expression(value);
		}
		if (types().isFloating(type)) {
			return call(name("formatStr"), Expression.string("{}"), body.expression(value));
		}
		if (types().isIntegral(type) || types().isBoolean(type) || type.getKind() == TypeKind.NULL) {
			return call(name("tostring"), body.expression(value));
		}
		return objectText(value, type);
	}

	/**
	 * A list, set or map as Java writes it, {@code [a, b]} or {@code {k=v}}, through a helper. Its elements must be
	 * strings, numbers or booleans, which Lua writes as Java does; anything else Lua writes as a table address.
	 */
	private Expression collectionText(ExpressionTree value, TypeMirror type) {
		boolean isMap = types().isMap(type);
		for (int index = 0; index < (isMap ? 2 : 1); index++) {
			TypeMirror element = types().typeArgument(type, index);
			if (!types().isString(element) && !types().isIntegral(element) && !types().isFloating(element)
					&& !types().isBoolean(element) && !types().isChar(element)) {
				throw new Rejection(value, "Lua would write each " + element + " as its table address, not as Java's"
						+ " toString(); join the elements with String.join or a loop.");
			}
		}
		Helper helper = isMap ? Helper.MAP_TEXT : types().isSet(type) ? Helper.SET_TEXT : Helper.LIST_TEXT;
		body.unit.use(helper);
		return helper.invoke(body.expression(value));
	}

	/** A plugin object's {@code toString()}, an enum value as it is, or a {@code StringBuilder}'s parts joined. */
	private Expression objectText(ExpressionTree value, TypeMirror type) {
		if (types().isChar(type)) {
			// A char is a one-character string already
			return body.expression(value);
		}
		if (types().isList(type) || types().isSet(type) || types().isMap(type) || types().isDeque(type)) {
			return collectionText(value, type);
		}
		if (types().isStringBuilder(type)) {
			return call(field(name("table"), "concat"), body.expression(value));
		}
		if (types().isApiType(type) && ((DeclaredType) type).asElement() instanceof TypeElement api
				&& api.getKind() == ElementKind.ENUM) {
			return apiEnumName(value, api, value);
		}
		if (types().isApiType(type) || types().isThrowable(type)) {
			// The client's objects describe themselves through tostring; a caught error is its value
			return call(name("tostring"), body.expression(value));
		}
		TypeElement plugin = types().pluginType(type);
		if (plugin != null && JavaTypes.isRecord(plugin) && !hasOwnToString(plugin)) {
			throw new Rejection(value, "A record is a plain Lua table without a toString(); put its components into the"
					+ " text instead.");
		}
		if (plugin != null && plugin.getKind() == ElementKind.ENUM && !types().isObjectEnum(type)) {
			return body.expression(value);
		}
		if (plugin != null && plugin.getKind() == ElementKind.ENUM && !hasOwnToString(plugin)) {
			// An object enum's constant, which Java writes by its name
			return field(body.expression(value), "name");
		}
		if (plugin != null) {
			if (!hasOwnToString(plugin)) {
				throw new Rejection(value, plugin.getSimpleName() + " has no toString(), so Java would print its class"
						+ " name and hash code; give it a toString() method.");
			}
			return Expression.methodCall(body.expression(value), "toString");
		}
		throw Rejection.notYet(value, "Turning a " + type + " into text");
	}

	/**
	 * Whether a class of the plugin's has a toString() of its own, or inherits one from a class of the plugin's; the
	 * one javac gives a record does not count.
	 */
	private boolean hasOwnToString(TypeElement plugin) {
		return body.unit.elements.getAllMembers(plugin).stream()
				.anyMatch(member -> member.getKind() == ElementKind.METHOD
						&& member.getSimpleName().contentEquals("toString")
						&& types().isPluginType(JavaTypes.owner(member)) && body.unit.declaredInSource(member));
	}

	/**
	 * A client enum's value as its name, as Java writes it: the value is the client's number, which the table's
	 * {@code FromID} turns into the name, {@code GameState.FromID(state)}. A table without {@code FromID} gives its
	 * values no names.
	 */
	Expression apiEnumName(ExpressionTree value, TypeElement api, Tree where) {
		if (!JavaTypes.hasAnnotation(api, JavaTypes.LUA_FROM_ID)) {
			throw new Rejection(where, "The client's " + api.getSimpleName() + " table has no FromID to give a value's"
					+ " name; compare the value with " + api.getSimpleName() + "'s constants instead.");
		}
		return call(field(body.api().table(api, where), "FromID"), body.expression(value));
	}

	/**
	 * {@code String.format} with a literal format, as {@code formatStr} with each specifier translated: {@code %d} and
	 * {@code %s} to {@code {}}, {@code %.2f} to {@code {:.2f}}, widths with their alignment written out, because the
	 * client aligns strings left and Java aligns everything right.
	 */
	Expression format(MethodInvocationTree call) {
		List<? extends ExpressionTree> arguments = call.getArguments();
		if (!(arguments.getFirst() instanceof LiteralTree literal) || !(literal.getValue() instanceof String format)) {
			throw new Rejection(call, "String.format needs a literal format string, so that Oneiro can translate its"
					+ " specifiers when it compiles.");
		}
		List<? extends ExpressionTree> values = arguments.subList(1, arguments.size());
		boolean numbered = Pattern.compile("%\\d+\\$").matcher(format).find();
		StringBuilder template = new StringBuilder();
		List<Expression> formatArguments = new ArrayList<>();
		if (numbered) {
			values.forEach(value -> formatArguments.add(formatArgument(value)));
		}
		Matcher specifier = SPECIFIER.matcher(format);
		int textStart = 0;
		int ordinary = 0;
		while (specifier.find()) {
			template.append(escapeBraces(format.substring(textStart, specifier.start())));
			textStart = specifier.end();
			String conversion = specifier.group(5);
			if (conversion.equals("%")) {
				template.append('%');
				continue;
			}
			if (conversion.equals("n")) {
				template.append('\n');
				continue;
			}
			String explicit = specifier.group(1);
			int index = explicit != null
					? Integer.parseInt(explicit.substring(0, explicit.length() - 1)) - 1
					: ordinary++;
			if (index >= values.size()) {
				throw new Rejection(call, "This format has more specifiers than arguments, so Java would throw"
						+ " MissingFormatArgumentException.");
			}
			Placeholder placeholder = placeholder(call, conversion, nullToEmpty(specifier.group(2)), specifier.group(3),
					specifier.group(4), values.get(index), numbered);
			String position = numbered ? Integer.toString(index) : "";
			template.append('{').append(position).append(placeholder.options()).append('}');
			if (!numbered) {
				formatArguments.add(placeholder.argument());
			}
		}
		template.append(escapeBraces(format.substring(textStart)));
		List<Expression> callArguments = new ArrayList<>();
		callArguments.add(Expression.string(template.toString()));
		callArguments.addAll(formatArguments);
		return new Expression.Call(name("formatStr"), callArguments);
	}

	/** One translated specifier: the options after the colon, and the argument it formats. */
	private record Placeholder(String options, Expression argument) {
	}

	// The messages quote Java's format specifiers, which Error Prone mistakes for unused format strings
	@SuppressWarnings("OrphanedFormatString")
	private Placeholder placeholder(MethodInvocationTree call, String conversion, String flags, @Nullable String width,
			@Nullable String precision, ExpressionTree value, boolean numbered) {
		TypeMirror type = type(value);
		if (flags.contains(",")) {
			throw new Rejection(call, "The client's formatStr adds no digit grouping, so %,d cannot print as Java"
					+ " prints it.");
		}
		return switch (conversion) {
			case "s" -> new Placeholder(colon(alignment(flags, width) + nullToEmpty(precision)),
					formatArgument(value));
			case "d" -> {
				requireIntegral(value, type, conversion);
				boolean signOrZero = flags.contains("+") || flags.contains(" ") || flags.contains("0");
				if (types().isLong(type) && signOrZero) {
					// The client would round a long to a double, so Lua formats it, flags and all
					yield new Placeholder("", luaFormat(call, flags, width, conversion, value, numbered));
				}
				yield new Placeholder(colon(numberOptions(flags, width, null, "")), formatArgument(value));
			}
			case "f", "e" -> {
				if (!types().isIntegral(type) && !types().isFloating(type)) {
					throw new Rejection(value, "%" + conversion + " needs a number, so Java would throw"
							+ " IllegalFormatConversionException.");
				}
				yield new Placeholder(colon(numberOptions(flags, width, precision, conversion)),
						body.expression(value));
			}
			case "x", "X", "o" -> {
				requireIntegral(value, type, conversion);
				yield new Placeholder("", luaFormat(call, flags, width, conversion, value, numbered));
			}
			case "c", "C" -> {
				if (numbered && (!types().isChar(type) || conversion.equals("C"))) {
					throw Rejection.notYet(call, "Numbered format specifiers together with %" + conversion);
				}
				Expression character;
				if (types().isChar(type)) {
					character = body.expression(value);
				} else if (types().isIntegral(type) && !types().isLong(type)) {
					character = call(field(name("string"), "char"), body.expression(value));
				} else {
					throw new Rejection(value, "%c needs a char or an int, so Java would throw"
							+ " IllegalFormatConversionException.");
				}
				if (precision != null) {
					throw new Rejection(call, "%c takes no precision, so Java would throw"
							+ " IllegalFormatPrecisionException.");
				}
				yield new Placeholder(colon(alignment(flags, width)),
						conversion.equals("C") ? Expression.methodCall(character, "upper") : character);
			}
			case "b", "B" -> {
				if (!types().isBoolean(type)) {
					throw new Rejection(value, "Java's %b of anything but a boolean prints whether it is null; pass"
							+ " that test, such as value != null, instead.");
				}
				if (precision != null || (numbered && conversion.equals("B"))) {
					throw Rejection.notYet(call, "%" + conversion + " with a precision or a number");
				}
				Expression truth = conversion.equals("B")
						? Expression.methodCall(call(name("tostring"), body.expression(value)), "upper")
						: body.expression(value);
				yield new Placeholder(colon(alignment(flags, width)), truth);
			}
			case "g", "G" -> throw new Rejection(call, "The client's formatStr drops trailing zeros for %g, which Java"
					+ " keeps; use %f or %e.");
			case "t", "T" -> throw new Rejection(call, "The client has no os library to read the calendar from, so %t"
					+ " has nothing to format; format the parts yourself with %d.");
			default -> throw Rejection.notYet(call, "The format specifier %" + conversion);
		};
	}

	private void requireIntegral(ExpressionTree value, TypeMirror type, String conversion) {
		if (!types().isIntegral(type)) {
			throw new Rejection(value, "%" + conversion + " needs an integer, so Java would throw"
					+ " IllegalFormatConversionException.");
		}
	}

	/**
	 * An integer formatted by Lua's {@code string.format}, for the options the client refuses because it sees every
	 * number as a double. An {@code int} is masked to 32 bits so a negative value prints as Java prints it.
	 */
	private Expression luaFormat(MethodInvocationTree call, String flags, @Nullable String width, String conversion,
			ExpressionTree value, boolean numbered) {
		if (numbered) {
			throw Rejection.notYet(call, "Numbered format specifiers together with %" + conversion);
		}
		Expression number = body.expression(value);
		if (!types().isLong(type(value)) && !conversion.equals("d")) {
			number = binary(number, BinaryOperator.BIT_AND, new Expression.NumberLiteral("0xFFFFFFFF"));
		}
		String luaSpecifier = "%" + flags.replaceAll("[^-+ 0#]", "") + nullToEmpty(width) + conversion;
		return call(field(name("string"), "format"), Expression.string(luaSpecifier), number);
	}

	/** {@code :options}, or nothing when there are none. */
	private static String colon(String options) {
		return options.isEmpty() ? "" : ":" + options;
	}

	/** The alignment and width for fmtlib: {@code <8} for Java's {@code %-8s}, {@code >8} for {@code %8s}. */
	private static String alignment(String flags, @Nullable String width) {
		if (width == null) {
			return "";
		}
		return (flags.contains("-") ? "<" : ">") + width;
	}

	/** fmtlib's {@code [align][sign][0][width][.precision][type]} for a number. */
	private static String numberOptions(String flags, @Nullable String width, @Nullable String precision, String type) {
		boolean zeroPadded = flags.contains("0") && !flags.contains("-");
		String sign = flags.contains("+") ? "+" : flags.contains(" ") ? " " : "";
		String align = width != null && !zeroPadded ? (flags.contains("-") ? "<" : ">") : "";
		String size = width == null ? "" : (zeroPadded ? "0" : "") + width;
		return align + sign + size + nullToEmpty(precision) + type;
	}

	private static String escapeBraces(String text) {
		return text.replace("{", "{{").replace("}", "}}");
	}

	private static String nullToEmpty(@Nullable String text) {
		return text == null ? "" : text;
	}
}
