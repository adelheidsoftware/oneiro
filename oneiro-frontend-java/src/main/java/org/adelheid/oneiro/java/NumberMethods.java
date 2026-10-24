package org.adelheid.oneiro.java;

import static org.adelheid.oneiro.lua.Expression.binary;
import static org.adelheid.oneiro.lua.Expression.call;
import static org.adelheid.oneiro.lua.Expression.field;
import static org.adelheid.oneiro.lua.Expression.integer;
import static org.adelheid.oneiro.lua.Expression.methodCall;
import static org.adelheid.oneiro.lua.Expression.name;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import java.util.List;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import org.adelheid.oneiro.lua.BinaryOperator;
import org.adelheid.oneiro.lua.Expression;

/**
 * The boxed number classes, {@code Character} and {@code Math} in Lua: parsing and comparing numbers,
 * {@code Character}'s tests and case changes on the one-character string a char is, {@code Math}'s functions
 * as Lua's {@code math}, and constants such as {@code Integer.MAX_VALUE}.
 */
final class NumberMethods {

	private final LibraryCalls library;
	private final BodyLowering body;
	private final UnitLowering unit;
	private final JavaTypes types;

	NumberMethods(LibraryCalls library) {
		this.library = library;
		this.body = library.body;
		this.unit = body.unit;
		this.types = body.types();
	}

	boolean isBoxed(TypeMirror type) {
		return types.is(type, "java.lang.Integer") || types.is(type, "java.lang.Long")
				|| types.is(type, "java.lang.Double") || types.is(type, "java.lang.Float")
				|| types.is(type, "java.lang.Short") || types.is(type, "java.lang.Byte")
				|| types.is(type, "java.lang.Boolean") || types.is(type, "java.lang.Character");
	}

	Expression boxedMethod(MethodInvocationTree call, String name, Expression value,
			List<? extends ExpressionTree> arguments) {
		return switch (name) {
			case "intValue", "longValue", "doubleValue", "floatValue", "shortValue", "byteValue", "booleanValue",
					"charValue" -> value;
			case "equals" -> binary(value, BinaryOperator.EQUAL, body.expression(arguments.getFirst()));
			default -> throw Rejection.notYet(call, "Calling " + name + " on a boxed value");
		};
	}

	Expression boxedStatic(MethodInvocationTree call, String name, String owner,
			List<? extends ExpressionTree> arguments) {
		boolean floating = owner.equals("java.lang.Double") || owner.equals("java.lang.Float");
		return switch (name) {
			case "parseInt", "parseLong", "parseShort", "parseByte" ->
					call(name("tonumber"), body.expression(arguments.getFirst()), integer(10));
			case "parseDouble", "parseFloat" -> call(name("tonumber"), body.expression(arguments.getFirst()));
			case "toString" -> body.strings().toText(arguments.getFirst());
			case "valueOf" -> {
				ExpressionTree argument = arguments.getFirst();
				if (types.isString(unit.type(argument))) {
					yield floating
							? call(name("tonumber"), body.expression(argument))
							: call(name("tonumber"), body.expression(argument), integer(10));
				}
				yield body.expression(argument);
			}
			default -> throw Rejection.notYet(call, owner.replace("java.lang.", "") + "." + name);
		};
	}

	/**
	 * {@code Character}'s tests and case changes, on the one-character string a char is, through Lua's character
	 * classes, which know ASCII only: {@code Character.isDigit(c)} is {@code c:find("%d") ~= nil}.
	 */
	Expression characterStatic(MethodInvocationTree call, String name, List<? extends ExpressionTree> arguments) {
		ExpressionTree argument = arguments.getFirst();
		String characterClass = switch (name) {
			case "isDigit" -> "%d";
			case "isLetter", "isAlphabetic" -> "%a";
			case "isLetterOrDigit" -> "%w";
			case "isWhitespace", "isSpaceChar" -> "%s";
			case "isUpperCase" -> "%u";
			case "isLowerCase" -> "%l";
			default -> null;
		};
		StringMethods stringMethods = library.stringMethods();
		if (characterClass != null) {
			Expression character = stringMethods.characterOf(argument);
			Expression found = methodCall(character, "find", Expression.string(characterClass));
			return binary(found, BinaryOperator.NOT_EQUAL, new Expression.Nil());
		}
		if ((name.equals("toUpperCase") || name.equals("toLowerCase")) && !stringMethods.isCharType(argument)) {
			throw Rejection.notYet(call, "Character." + name + " of a number");
		}
		return switch (name) {
			case "toUpperCase" -> methodCall(stringMethods.characterOf(argument), "upper");
			case "toLowerCase" -> methodCall(stringMethods.characterOf(argument), "lower");
			case "toString", "valueOf" -> stringMethods.characterOf(argument);
			case "getNumericValue" -> digitValue(stringMethods.characterOf(argument), integer(36));
			case "digit" -> digitValue(stringMethods.characterOf(argument), body.expression(arguments.get(1)));
			default -> throw Rejection.notYet(call, "Character." + name);
		};
	}

	/**
	 * The value of a digit in the radix, or -1 when it is none. {@code tonumber} reads the letters after 9 as digits,
	 * {@code a} as 10, as Java does.
	 */
	private static Expression digitValue(Expression character, Expression radix) {
		return binary(call(name("tonumber"), character, radix), BinaryOperator.OR, integer(-1));
	}

	Expression mathMethod(MethodInvocationTree call, String name, List<? extends ExpressionTree> arguments) {
		List<Expression> lowered = body.arguments(arguments);
		return switch (name) {
			case "abs", "floor", "ceil", "sqrt", "max", "min" -> {
				if ((name.equals("max") || name.equals("min")) && lowered.size() != 2) {
					throw Rejection.notYet(call, "Math." + name);
				}
				yield new Expression.Call(field(name("math"), name), lowered);
			}
			case "random" -> call(field(name("math"), "random"));
			case "pow" -> binary(lowered.get(0), BinaryOperator.POWER, lowered.get(1));
			case "floorDiv" -> binary(lowered.get(0), BinaryOperator.FLOOR_DIVIDE, lowered.get(1));
			case "floorMod" -> binary(lowered.get(0), BinaryOperator.MODULO, lowered.get(1));
			// Java defines Math.round as the floor of x + 0.5
			case "round" -> call(field(name("math"), "floor"),
					binary(lowered.getFirst(), BinaryOperator.ADD, new Expression.NumberLiteral("0.5")));
			default -> throw Rejection.notYet(call, "Math." + name);
		};
	}

	/** {@code Integer.MAX_VALUE}, {@code Long.MAX_VALUE}, {@code Math.PI} and their kin. */
	Expression staticField(VariableElement field, Tree where) {
		String owner = JavaTypes.owner(field).getQualifiedName().toString();
		String name = field.getSimpleName().toString();
		return switch (owner + "." + name) {
			case "java.lang.Integer.MAX_VALUE" -> integer(Integer.MAX_VALUE);
			case "java.lang.Integer.MIN_VALUE" -> integer(Integer.MIN_VALUE);
			case "java.lang.Long.MAX_VALUE" -> field(name("math"), "maxinteger");
			case "java.lang.Long.MIN_VALUE" -> field(name("math"), "mininteger");
			case "java.lang.Math.PI" -> field(name("math"), "pi");
			default -> throw Rejection.notYet(where, owner.replaceAll(".*\\.", "") + "." + name);
		};
	}
}
