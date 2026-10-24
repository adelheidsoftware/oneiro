package org.adelheid.oneiro.java;

import static org.adelheid.oneiro.lua.Expression.name;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.ElementFilter;
import org.adelheid.oneiro.lua.Expression;
import org.jspecify.annotations.Nullable;

/**
 * Calls of the plugin's own methods: a static method through its module, an instance method with a colon, a
 * private one as a local function, the superclass's method through {@code super}, a setting through the
 * settings module, and the {@code values()} and {@code valueOf} that javac gives every enum.
 */
final class PluginCalls {

	private final BodyLowering body;
	private final UnitLowering unit;
	private final JavaTypes types;

	PluginCalls(BodyLowering body) {
		this.body = body;
		this.unit = body.unit;
		this.types = body.types();
	}

	/**
	 * A call of a plugin method: {@code Module.f(...)} for a static method, {@code object:f(...)} for an instance
	 * method, {@code f(object, ...)} for a private one, which is a local function, {@code Base.f(self, ...)} through
	 * {@code super}, and {@code f(...)} for the method of an interface whose values are plain functions.
	 */
	Expression invocation(MethodInvocationTree call, ExecutableElement method, TypeElement owner,
			@Nullable ExpressionTree receiver) {
		unit.checkReachable(owner, call);
		// values() and valueOf(), which javac gives every enum; a static method the enum declares is a plain one
		if (owner.getKind() == ElementKind.ENUM && method.getModifiers().contains(Modifier.STATIC)
				&& !unit.declaredInSource(method)) {
			return enumStatic(call, method, owner);
		}
		if (unit.isLifecycle(method)) {
			throw new Rejection(call, method.getSimpleName() + "() runs once, when the plugin "
					+ (method.getSimpleName().contentEquals("startUp") ? "starts" : "stops")
					+ "; move what it shares into a method of its own and call that.");
		}
		List<Expression> arguments = body.arguments(method, call.getArguments());
		if (types.configGroup(owner) != null) {
			// Settings are functions of the settings module, called with a dot
			return new Expression.Call(Expression.field(unit.moduleReference(owner), types.memberName(method)),
					arguments);
		}
		if (method.getModifiers().contains(Modifier.STATIC) || unit.isEntry(owner)) {
			return new Expression.Call(unit.staticMember(owner, method), arguments);
		}
		if (BodyLowering.isSuper(receiver)) {
			TypeElement superclass = unit.pluginSuperclass(unit.enclosingClass(call));
			if (superclass == null) {
				throw new IllegalStateException("super call without a plugin superclass at " + unit.origin(call));
			}
			List<Expression> withSelf = new ArrayList<>();
			withSelf.add(body.self(call));
			withSelf.addAll(arguments);
			return new Expression.Call(Expression.field(unit.moduleReference(superclass), types.memberName(method)),
					withSelf);
		}
		Expression target = receiver == null ? body.receiverFor(owner, call)
				: BodyLowering.isThis(receiver) ? body.self(call)
				: body.expression(receiver);
		if (JavaTypes.isRecord(owner)) {
			if (JavaTypes.isRecordAccessor(method)) {
				return Expression.field(target, types.memberName(method));
			}
			// equals, hashCode and toString that the record does not declare itself
			if (!types.isObjectRecord(owner) || !unit.declaredInSource(method)) {
				throw new Rejection(call, "A record is a plain Lua table, compared by identity and without methods; use"
						+ " its components instead.");
			}
		}
		if (types.isFunctionalPluginInterface(owner)) {
			if (method.getModifiers().contains(Modifier.DEFAULT)) {
				throw new Rejection(call, "No class of the plugin's implements " + owner.getSimpleName() + ", so its"
						+ " values are Lua functions, which carry no methods; implement it with a class to call "
						+ method.getSimpleName() + "(), or make " + method.getSimpleName() + "() a static method.");
			}
			return new Expression.Call(target, arguments);
		}
		if (method.getModifiers().contains(Modifier.PRIVATE)) {
			List<Expression> withTarget = new ArrayList<>();
			withTarget.add(target);
			withTarget.addAll(arguments);
			return new Expression.Call(name(unit.localName(method)), withTarget);
		}
		return new Expression.MethodCall(target, types.memberName(method), arguments);
	}

	/**
	 * An enum's {@code values()}, a new list of its constants in order as Java's is a new array, and
	 * {@code valueOf(name)}, the constant's table entry; Lua gives nil for a name that is not a constant.
	 */
	private Expression enumStatic(MethodInvocationTree call, ExecutableElement method, TypeElement owner) {
		String name = method.getSimpleName().toString();
		if (name.equals("values") && call.getArguments().isEmpty()) {
			List<Expression.TableField> constants = new ArrayList<>();
			for (VariableElement field : ElementFilter.fieldsIn(owner.getEnclosedElements())) {
				if (field.getKind() == ElementKind.ENUM_CONSTANT) {
					constants.add(new Expression.TableField.Positional(body.enumConstant(field, call)));
				}
			}
			return new Expression.Table(constants);
		}
		if (name.equals("valueOf") && call.getArguments().size() == 1) {
			return new Expression.Index(unit.moduleReference(owner), body.expression(call.getArguments().getFirst()));
		}
		throw Rejection.notYet(call, "The enum method " + name);
	}
}
