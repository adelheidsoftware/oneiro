package org.adelheid.oneiro.java;

import static org.adelheid.oneiro.lua.Expression.binary;
import static org.adelheid.oneiro.lua.Expression.call;
import static org.adelheid.oneiro.lua.Expression.name;

import com.sun.source.tree.BindingPatternTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import java.util.List;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import org.adelheid.oneiro.lowering.Helper;
import org.adelheid.oneiro.lua.BinaryOperator;
import org.adelheid.oneiro.lua.Expression;

/**
 * Java's type tests in Lua: {@code instanceof}, with or without a type pattern, and the test a type pattern in a
 * switch makes. A pattern's variable stands for the value it tests, which is not copied into a local of its own.
 */
final class TypeTests {

	private final BodyLowering body;
	private final UnitLowering unit;
	private final JavaTypes types;

	TypeTests(BodyLowering body) {
		this.body = body;
		this.unit = body.unit;
		this.types = body.types();
	}

	/**
	 * {@code x instanceof T}, and with a pattern, {@code x instanceof T t}, whose variable stands for {@code x}, which
	 * must read the same each time.
	 */
	Expression instanceOf(InstanceOfTree instance) {
		ExpressionTree operand = instance.getExpression();
		Expression value = body.expression(operand);
		Tree pattern = instance.getPattern();
		if (pattern == null) {
			return typeTest(value, unit.type(instance.getType()), instance);
		}
		VariableTree variable = patternVariable(pattern);
		if (SourceFacts.containsCall(operand)) {
			throw new Rejection(operand, "The pattern's variable stands for this value, which Lua would read again each"
					+ " time; store it in a local first.");
		}
		body.declare(variable);
		body.alias(variable, value);
		return typeTest(value, unit.type(variable), instance);
	}

	/** A type pattern's variable; a record pattern, which would take the record apart, has no Lua form. */
	static VariableTree patternVariable(Tree pattern) {
		if (pattern instanceof BindingPatternTree binding) {
			return binding.getVariable();
		}
		throw new Rejection(pattern, "Lua has no patterns that take a record apart; test the type, then read the"
				+ " record's components.");
	}

	/**
	 * A test that a value is of a type, as Java's instanceof. A class of the plugin's is its objects' metatable,
	 * {@code getmetatable(x) == Square}, through the helper {@code isInstance} for a class that others extend; an
	 * interface is the classes that implement it; a string, number or boolean is Lua's own type; a simple enum's
	 * constant is one of its strings, {@code Mode[x] == x}.
	 */
	Expression typeTest(Expression value, TypeMirror type, Tree where) {
		if (types.isString(type) || types.isChar(type)) {
			return binary(call(name("type"), value), BinaryOperator.EQUAL, Expression.string("string"));
		}
		if (types.isBoolean(type)) {
			return binary(call(name("type"), value), BinaryOperator.EQUAL, Expression.string("boolean"));
		}
		if (types.isIntegral(type) || types.isFloating(type)) {
			return binary(call(Expression.field(name("math"), "type"), value), BinaryOperator.EQUAL,
					Expression.string(types.isIntegral(type) ? "integer" : "float"));
		}
		if (types.is(type, "java.lang.Number")) {
			return binary(call(name("type"), value), BinaryOperator.EQUAL, Expression.string("number"));
		}
		TypeElement plugin = types.pluginType(type);
		if (plugin == null) {
			throw new Rejection(where, "Lua cannot tell the client's objects, or lists, maps and other tables, apart by"
					+ " type; test what the value holds instead.");
		}
		if (plugin.getKind() == ElementKind.ENUM && !types.isObjectEnum(type)) {
			return binary(new Expression.Index(unit.moduleReference(plugin), value), BinaryOperator.EQUAL, value);
		}
		if (JavaTypes.isRecord(plugin) && !types.isObjectRecord(plugin)) {
			throw new Rejection(where, "A record with only its components is a plain Lua table, which Lua cannot tell"
					+ " from other tables; give the record a method, which makes its values objects of a class, or test"
					+ " what the value holds instead.");
		}
		if (types.isFunctionalPluginInterface(plugin)) {
			return binary(call(name("type"), value), BinaryOperator.EQUAL, Expression.string("function"));
		}
		List<TypeElement> roots = plugin.getKind() == ElementKind.INTERFACE
				? types.instanceRoots(plugin)
				: List.of(plugin);
		Expression test = null;
		for (TypeElement root : roots) {
			Expression one;
			if (types.hasPluginSubclass(root)) {
				unit.use(Helper.IS_INSTANCE);
				one = Helper.IS_INSTANCE.invoke(value, unit.moduleReference(root));
			} else {
				one = binary(call(name("getmetatable"), value), BinaryOperator.EQUAL, unit.moduleReference(root));
			}
			test = test == null ? one : binary(test, BinaryOperator.OR, one);
		}
		if (test == null) {
			throw new Rejection(where, "No class of the plugin's implements " + plugin.getSimpleName() + ", so no value"
					+ " is one.");
		}
		return test;
	}
}
