package org.adelheid.oneiro.java.library;

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
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.adelheid.oneiro.java.core.BodyLowering;
import org.adelheid.oneiro.java.core.JavaTypes;
import org.adelheid.oneiro.java.core.Rejection;
import org.adelheid.oneiro.lowering.SourceLanguage;
import org.adelheid.oneiro.lowering.client.ClientApi;
import org.adelheid.oneiro.lowering.translation.FormatTranslation;
import org.adelheid.oneiro.lowering.translation.Helper;
import org.adelheid.oneiro.lua.BinaryOperator;
import org.adelheid.oneiro.lua.Expression;
import org.jspecify.annotations.Nullable;

/**
 * Java's string building in the house style: {@code +} concatenation and {@code String.format} become one call of the
 * client's {@code formatStr}, and values become text by their Java type, as docs/java-subset.md lays out. The client
 * hands every number to {@code formatStr} as a double, so a {@code long} goes through {@code tostring} to stay exact,
 * and integer options such as {@code %x} are formatted by Lua's {@code string.format} inside the template.
 */
public final class StringBuilding {

	private final BodyLowering body;

	public StringBuilding(BodyLowering body) {
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
	public Expression concatenation(BinaryTree concatenation) {
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
				template.append(FormatTranslation.escapeBraces(text));
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
		return ClientApi.formatStr(template.toString(), arguments);
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
	public Expression concatOperand(ExpressionTree value) {
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
	public Expression asText(ExpressionTree value) {
		TypeMirror type = type(value);
		if (types().isString(type) || types().isIntegral(type)) {
			return body.expression(value);
		}
		if (types().isFloating(type)) {
			return ClientApi.formatStr("{}", List.of(body.expression(value)));
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
			return ClientApi.formatStr("{}", List.of(body.expression(value)));
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
			throw new Rejection(where, ClientApi.noFromId(api.getSimpleName().toString()));
		}
		return ClientApi.fromId(body.api().table(api, where), body.expression(value));
	}

	/**
	 * {@code String.format} with a literal format, as one call of {@code formatStr}, through
	 * {@link FormatTranslation}.
	 */
	Expression format(MethodInvocationTree call) {
		List<? extends ExpressionTree> arguments = call.getArguments();
		if (!(arguments.getFirst() instanceof LiteralTree literal) || !(literal.getValue() instanceof String format)) {
			throw new Rejection(call, "String.format needs a literal format string, so that Oneiro can translate its"
					+ " specifiers when it compiles.");
		}
		List<? extends ExpressionTree> values = arguments.subList(1, arguments.size());
		FormatTranslation.Values translated = new FormatTranslation.Values() {
			@Override
			public int size() {
				return values.size();
			}

			@Override
			public FormatTranslation.Kind kind(int index) {
				return formatKind(type(values.get(index)));
			}

			@Override
			public Expression formatArgument(int index) {
				return StringBuilding.this.formatArgument(values.get(index));
			}

			@Override
			public Expression expression(int index) {
				return body.expression(values.get(index));
			}
		};
		try {
			return FormatTranslation.translate(format, translated, SourceLanguage.JAVA);
		} catch (FormatTranslation.Problem problem) {
			Tree where = problem.value() < 0 ? call : values.get(problem.value());
			throw problem.notYet() ? Rejection.notYet(where, problem.text()) : new Rejection(where, problem.text());
		}
	}

	/** What kind of value a format formats, by its Java type. */
	private FormatTranslation.Kind formatKind(TypeMirror type) {
		if (types().isChar(type)) {
			return FormatTranslation.Kind.CHAR;
		}
		if (types().isLong(type)) {
			return FormatTranslation.Kind.LONG;
		}
		if (types().isIntegral(type)) {
			return FormatTranslation.Kind.INTEGER;
		}
		if (types().isFloating(type)) {
			return FormatTranslation.Kind.FLOATING;
		}
		if (types().isBoolean(type)) {
			return FormatTranslation.Kind.BOOLEAN;
		}
		return types().isString(type) ? FormatTranslation.Kind.STRING : FormatTranslation.Kind.OTHER;
	}
}
