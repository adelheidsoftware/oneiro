package org.adelheid.oneiro.java;

import static org.adelheid.oneiro.lua.Expression.name;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import org.adelheid.oneiro.lua.BinaryOperator;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.LuaNames;
import org.jspecify.annotations.Nullable;

/**
 * Calls into Lua that the client provides, declared by classes annotated {@code @LuaGlobal} (a global table, such as
 * {@code Player} or {@code ui.Text}) or {@code @LuaType} (values the client hands out, such as {@code NPCEntity}): the
 * RS3 bindings, and the plugin's generated {@code Resources} and {@code Urls}. Each member's annotations give its Lua
 * shape: a static method is {@code Table.Name(...)}, or {@code Table:Name(...)} with {@code @LuaColon}; an instance
 * method is {@code object:Name(...)}, or {@code object.Name(...)} with {@code @LuaDot}; a constructor is
 * {@code Table.new(...)}, or {@code {}} for a {@code @LuaType} table the plugin fills in. Members that need a
 * permission the manifest does not declare, or that must never ship, are rejected here.
 */
final class ApiCalls {

	private final BodyLowering body;

	ApiCalls(BodyLowering body) {
		this.body = body;
	}

	private JavaTypes types() {
		return body.types();
	}

	private UnitLowering unit() {
		return body.unit;
	}

	Expression invocation(MethodInvocationTree call, ExecutableElement method, TypeElement owner,
			@Nullable ExpressionTree receiver) {
		checkUse(method, call);
		List<Expression> arguments = arguments(method, call.getArguments());
		String name = types().memberName(method);
		boolean isStatic = method.getModifiers().contains(Modifier.STATIC);
		if (JavaTypes.hasAnnotation(method, JavaTypes.LUA_INDEX)) {
			Expression indexed = isStatic ? table(owner, call) : target(receiver, call);
			return new Expression.Index(indexed, arguments.getFirst());
		}
		if (JavaTypes.hasAnnotation(method, JavaTypes.LUA_SAME)) {
			// ui.Hook.ONCLICK.stoppable() only retypes the hook for Java; in Lua it is the hook itself
			return target(receiver, call);
		}
		String operator = JavaTypes.operator(method);
		if (operator != null) {
			// a.plus(b) on the client's vectors is Lua's own a + b
			return new Expression.Binary(binaryOperator(operator), target(receiver, call), arguments.getFirst());
		}
		if (isStatic) {
			String global = global(owner, call);
			if (global.isEmpty()) {
				unit().useGlobal(name);
				return new Expression.Call(name(name), arguments);
			}
			Expression table = table(owner, call);
			return JavaTypes.hasAnnotation(method, JavaTypes.LUA_COLON)
					? new Expression.MethodCall(table, name, arguments)
					: new Expression.Call(Expression.field(table, name), arguments);
		}
		Expression target = target(receiver, call);
		return JavaTypes.hasAnnotation(method, JavaTypes.LUA_DOT)
				? new Expression.Call(Expression.field(target, name), arguments)
				: new Expression.MethodCall(target, name, arguments);
	}

	/**
	 * A method reference to a client function: a static function as it is, {@code Chat.AddMessage}, and an object's
	 * method as a function that calls it, {@code function(npc) return npc:GetName() end}.
	 *
	 * @param receiver the object a bound reference calls the method on; null for a static or unbound one
	 */
	Expression reference(MemberReferenceTree reference, ExecutableElement method, TypeElement owner,
			@Nullable Expression receiver) {
		checkUse(method, reference);
		if (JavaTypes.hasAnnotation(method, JavaTypes.LUA_INDEX) || JavaTypes.hasAnnotation(method, JavaTypes.LUA_SAME)
				|| JavaTypes.operator(method) != null || reference.getMode() == MemberReferenceTree.ReferenceMode.NEW) {
			throw new Rejection(reference, "This client member is no Lua function; write the reference as a lambda,"
					+ " x -> ..., which Oneiro lowers in place.");
		}
		String name = types().memberName(method);
		List<String> parameters = body.methodReferences().parameters(method, reference);
		List<Expression> arguments = parameters.stream().map(parameter -> (Expression) name(parameter)).toList();
		boolean isVoid = method.getReturnType().getKind() == TypeKind.VOID;
		if (method.getModifiers().contains(Modifier.STATIC)) {
			String global = global(owner, reference);
			if (global.isEmpty()) {
				unit().useGlobal(name);
				return name(name);
			}
			Expression table = table(owner, reference);
			return JavaTypes.hasAnnotation(method, JavaTypes.LUA_COLON)
					? MethodReferences.function(parameters, new Expression.MethodCall(table, name, arguments), isVoid)
					: Expression.field(table, name);
		}
		List<String> all = new ArrayList<>();
		Expression target = receiver;
		if (target == null) {
			String object = unit().freshLocal(MethodReferences.decapitalized(owner), reference);
			all.add(object);
			target = name(object);
		}
		all.addAll(parameters);
		Expression invoked = JavaTypes.hasAnnotation(method, JavaTypes.LUA_DOT)
				? new Expression.Call(Expression.field(target, name), arguments)
				: new Expression.MethodCall(target, name, arguments);
		return MethodReferences.function(all, invoked, isVoid);
	}

	private static BinaryOperator binaryOperator(String operator) {
		return switch (operator) {
			case "+" -> BinaryOperator.ADD;
			case "-" -> BinaryOperator.SUBTRACT;
			case "*" -> BinaryOperator.MULTIPLY;
			case "/" -> BinaryOperator.DIVIDE;
			default -> throw new IllegalStateException("an operator the bindings should not give: " + operator);
		};
	}

	/** A field: of the global table for a static one, {@code Player.x}, or of the object, {@code npc.coordGrid}. */
	Expression field(VariableElement field, @Nullable ExpressionTree receiver, Tree where) {
		checkUse(field, where);
		TypeElement owner = JavaTypes.owner(field);
		String name = types().memberName(field);
		if (field.getModifiers().contains(Modifier.STATIC)) {
			String global = global(owner, where);
			if (global.isEmpty()) {
				unit().useGlobal(name);
				return name(name);
			}
			return member(table(owner, where), name);
		}
		return member(target(receiver, where), name);
	}

	/** An API enum's constant, {@code GameState.GAME}, whose value is the client's own number or string. */
	Expression enumConstant(VariableElement constant, Tree where) {
		checkUse(constant, where);
		return member(table(JavaTypes.owner(constant), where), types().memberName(constant));
	}

	/**
	 * {@code new CoordGrid(x, y, z)} as the table's constructor, {@code CoordGrid.new(x, y, z)}; {@code new} of a
	 * {@code @LuaType} as an empty table, which the plugin fills in and passes to the API.
	 */
	Expression construct(NewClassTree creation, ExecutableElement constructor, TypeElement type) {
		checkUse(constructor, creation);
		List<Expression> arguments = arguments(constructor, creation.getArguments());
		if (types().luaGlobal(type) == null) {
			if (!arguments.isEmpty()) {
				throw new IllegalStateException("a @LuaType constructor with parameters: " + type);
			}
			return new Expression.Table(List.of());
		}
		return new Expression.Call(Expression.field(table(type, creation), "new"), arguments);
	}

	/**
	 * The arguments of a call, as Java passes them: a variable-arity call passes its values one by one, which Lua's
	 * {@code ...} receives as they are, and an array passed for them is spread with {@code table.unpack}, as Java
	 * spreads it over the parameters.
	 */
	private List<Expression> arguments(ExecutableElement method, List<? extends ExpressionTree> arguments) {
		if (method.isVarArgs() && arguments.size() == method.getParameters().size()
				&& unit().type(arguments.getLast()).getKind() == TypeKind.ARRAY) {
			List<Expression> lowered = new ArrayList<>(body.arguments(arguments.subList(0, arguments.size() - 1)));
			unit().useGlobal("table");
			lowered.add(new Expression.Call(Expression.field(name("table"), "unpack"),
					List.of(body.expression(arguments.getLast()))));
			return lowered;
		}
		return body.arguments(arguments);
	}

	/** The Lua path of the class's table; a {@code @LuaType} has none. */
	private String global(TypeElement owner, Tree where) {
		String global = types().luaGlobal(owner);
		if (global == null) {
			throw new IllegalStateException("a static member of a @LuaType at " + unit().origin(where));
		}
		return global;
	}

	/** A global table as an expression: {@code ui.Text} is the field {@code Text} of the global {@code ui}. */
	Expression table(TypeElement owner, Tree where) {
		String global = global(owner, where);
		List<String> segments = List.of(global.split("\\.", -1));
		unit().useGlobal(segments.getFirst());
		Expression table = name(segments.getFirst());
		for (String segment : segments.subList(1, segments.size())) {
			table = member(table, segment);
		}
		return table;
	}

	private Expression target(@Nullable ExpressionTree receiver, Tree where) {
		if (receiver == null) {
			throw new IllegalStateException("an API instance member without a receiver at " + unit().origin(where));
		}
		return body.expression(receiver);
	}

	/** {@code table.name}, or {@code table["name"]} for a key that is not a Lua name, such as an enum's. */
	private static Expression member(Expression table, String name) {
		return LuaNames.isName(name) ? Expression.field(table, name) : new Expression.Index(table, Expression.string(name));
	}

	/**
	 * Rejects what the build does not allow: a member that needs a permission the manifest does not declare, and a
	 * development-only member outside the {@code dev} source set.
	 */
	private void checkUse(Element member, Tree where) {
		if (JavaTypes.hasOwnOrClass(member, JavaTypes.LUA_DEV_ONLY) && !unit().plugin.dev()) {
			throw new Rejection(where, display(member) + " is for development only and must never ship; move the code"
					+ " that uses it into the dev source set (src/dev/java), whose Lua stays out of the zip.");
		}
		String permission = types().inherited(member, JavaTypes.LUA_PERMISSION);
		if (permission != null && !unit().plugin.permissions().contains(permission)) {
			throw new Rejection(where, display(member) + " needs the " + permission + " permission, which the manifest"
					+ " does not declare; add it to the build, oneiro { plugin { permissions { optional(" + permission
					+ ") } } }, and check CurrentPlugin.HasPermission(PluginPermissions."
					+ permission + ") before using it.");
		}
	}

	/** {@code Chat.AddMessage}, {@code new HTTPRequest}, {@code log}: a member as the developer wrote it. */
	private String display(Element member) {
		TypeElement owner = JavaTypes.owner(member);
		if (member.getSimpleName().contentEquals("<init>")) {
			return "new " + owner.getSimpleName();
		}
		String global = types().luaGlobal(owner);
		return global != null && global.isEmpty()
				? types().memberName(member)
				: owner.getSimpleName() + "." + types().memberName(member);
	}
}
