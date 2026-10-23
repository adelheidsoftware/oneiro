package org.adelheid.oneiro.java;

import static org.adelheid.oneiro.lua.Expression.binary;
import static org.adelheid.oneiro.lua.Expression.call;
import static org.adelheid.oneiro.lua.Expression.field;
import static org.adelheid.oneiro.lua.Expression.integer;
import static org.adelheid.oneiro.lua.Expression.name;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.adelheid.oneiro.lua.BinaryOperator;
import org.adelheid.oneiro.lua.Block;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.FunctionBody;
import org.adelheid.oneiro.lua.Statement;
import org.adelheid.oneiro.lua.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * Method references as Lua functions. A function Lua has already stands as it is: a static method of the plugin's,
 * {@code Herbs.isLong}, a constructor, {@code Tile.new}, a client function, {@code Chat.AddMessage}, or
 * {@code math.max}. Anything else is a small function that makes the call, its parameters named as the method names
 * them: {@code Herb::describe} is {@code function(herb) return herb:describe() end}, and {@code planter::accept} is
 * {@code function(herb) return planter:accept(herb) end}. A library method has a Lua form only where Oneiro knows
 * one; for the rest, a lambda says the same.
 */
final class MethodReferences {

	private final BodyLowering body;

	MethodReferences(BodyLowering body) {
		this.body = body;
	}

	private JavaTypes types() {
		return body.types();
	}

	private UnitLowering unit() {
		return body.unit;
	}

	Expression lower(MemberReferenceTree reference) {
		if (!(unit().element(reference) instanceof ExecutableElement method)) {
			throw unsupported(reference);
		}
		TypeElement owner = JavaTypes.owner(method);
		boolean isStatic = method.getModifiers().contains(Modifier.STATIC);
		boolean constructor = reference.getMode() == MemberReferenceTree.ReferenceMode.NEW;
		ExpressionTree qualifier = reference.getQualifierExpression();
		if (method.isVarArgs()) {
			throw unsupported(reference);
		}
		// Herb::describe names the method of a class; planter::accept, a method of one object
		boolean unbound = !isStatic && !constructor && isTypeName(qualifier);
		if (types().isApi(owner)) {
			return body.api().reference(reference, method, owner, isStatic || unbound
					? null : boundReceiver(reference));
		}
		if (types().isPluginType(owner)) {
			return pluginReference(reference, method, owner, isStatic, constructor, unbound);
		}
		return libraryReference(reference, method, owner, isStatic, constructor, unbound);
	}

	private Expression pluginReference(MemberReferenceTree reference, ExecutableElement method, TypeElement owner,
			boolean isStatic, boolean constructor, boolean unbound) {
		unit().checkReachable(owner, reference);
		if (constructor) {
			if (JavaTypes.isRecord(owner) && !types().isObjectRecord(owner)) {
				// A plain record is a table of its components
				List<String> parameters = parameters(method, reference);
				List<Expression.TableField> fields = new ArrayList<>();
				for (int index = 0; index < parameters.size(); index++) {
					fields.add(new Expression.TableField.Named(
							types().memberName(owner.getRecordComponents().get(index).getAccessor()),
							name(parameters.get(index))));
				}
				return function(parameters, new Expression.Table(fields), false);
			}
			return field(unit().moduleReference(owner), "new");
		}
		if (isStatic || unit().isEntry(owner)) {
			// The plugin class's methods are local functions taking the same parameters
			return unit().staticMember(owner, method);
		}
		List<String> parameters = parameters(method, reference);
		Expression receiver;
		List<String> all = new ArrayList<>();
		if (unbound) {
			String object = unit().freshLocal(decapitalized(owner), reference);
			all.add(object);
			receiver = name(object);
		} else {
			receiver = boundReceiver(reference);
		}
		all.addAll(parameters);
		List<Expression> arguments = parameters.stream().map(parameter -> (Expression) name(parameter)).toList();
		Expression invoked;
		if (JavaTypes.isRecordAccessor(method)) {
			invoked = field(receiver, types().memberName(method));
		} else if (method.getModifiers().contains(Modifier.PRIVATE)) {
			List<Expression> withReceiver = new ArrayList<>();
			withReceiver.add(receiver);
			withReceiver.addAll(arguments);
			invoked = new Expression.Call(name(unit().localName(method)), withReceiver);
		} else if (types().isFunctionalPluginInterface(owner)) {
			invoked = new Expression.Call(receiver, arguments);
		} else {
			invoked = new Expression.MethodCall(receiver, types().memberName(method), arguments);
		}
		return function(all, invoked, method.getReturnType().getKind() == TypeKind.VOID);
	}

	/**
	 * A library method Oneiro knows a Lua form for: a function Lua has, such as {@code math.max} and {@code print}, or
	 * a small function of the method's Lua form, such as {@code function(text) return text == "" end} for
	 * {@code String::isEmpty}.
	 */
	private Expression libraryReference(MemberReferenceTree reference, ExecutableElement method, TypeElement owner,
			boolean isStatic, boolean constructor, boolean unbound) {
		String ownerName = owner.getQualifiedName().toString();
		String name = method.getSimpleName().toString();
		if (constructor) {
			return switch (ownerName) {
				case "java.util.ArrayList", "java.util.HashMap", "java.util.HashSet", "java.lang.StringBuilder",
						"java.util.LinkedList", "java.util.ArrayDeque" -> {
					if (!method.getParameters().isEmpty()) {
						throw unsupported(reference);
					}
					yield function(List.of(), new Expression.Table(List.of()), false);
				}
				default -> throw unsupported(reference);
			};
		}
		if (isSystemOut(reference.getQualifierExpression()) && name.equals("println")) {
			unit().useGlobal("print");
			return name("print");
		}
		if (isStatic) {
			return switch (ownerName + "." + name) {
				case "java.lang.Math.abs", "java.lang.Math.max", "java.lang.Math.min", "java.lang.Math.sqrt",
						"java.lang.Math.floor", "java.lang.Math.ceil", "java.lang.Integer.max", "java.lang.Integer.min",
						"java.lang.Long.max", "java.lang.Long.min", "java.lang.Double.max", "java.lang.Double.min" ->
						field(name("math"), name);
				case "java.lang.Integer.sum", "java.lang.Long.sum", "java.lang.Double.sum" ->
						function(List.of("a", "b"), binary(name("a"), BinaryOperator.ADD, name("b")), false);
				case "java.lang.Integer.parseInt", "java.lang.Long.parseLong" -> function(List.of("text"),
						call(name("tonumber"), name("text"), integer(10)), false);
				case "java.lang.Double.parseDouble" -> name("tonumber");
				case "java.lang.String.valueOf", "java.util.Objects.toString" -> name("tostring");
				case "java.util.Objects.nonNull" -> function(List.of("value"),
						binary(name("value"), BinaryOperator.NOT_EQUAL, new Expression.Nil()), false);
				case "java.util.Objects.isNull" -> function(List.of("value"),
						binary(name("value"), BinaryOperator.EQUAL, new Expression.Nil()), false);
				default -> throw unsupported(reference);
			};
		}
		if (unbound) {
			Expression text = name("text");
			return switch (ownerName + "." + name) {
				case "java.lang.String.isEmpty" -> function(List.of("text"),
						binary(text, BinaryOperator.EQUAL, Expression.string("")), false);
				case "java.lang.String.length" -> function(List.of("text"),
						new Expression.Unary(UnaryOperator.LENGTH, text), false);
				case "java.lang.String.toUpperCase" -> function(List.of("text"), Expression.methodCall(text, "upper"),
						false);
				case "java.lang.String.toLowerCase" -> function(List.of("text"), Expression.methodCall(text, "lower"),
						false);
				case "java.lang.String.trim", "java.lang.String.strip" -> {
					unit().use(Helper.TRIM);
					yield function(List.of("text"), Helper.TRIM.invoke(text), false);
				}
				case "java.lang.String.concat" -> function(List.of("a", "b"),
						binary(name("a"), BinaryOperator.CONCAT, name("b")), false);
				case "java.lang.Object.toString", "java.lang.String.toString" -> name("tostring");
				default -> throw unsupported(reference);
			};
		}
		// list::add and its kin, on a collection that is a plain name, which the function reads when it runs
		Expression collection = boundReceiver(reference);
		TypeMirror type = unit().type(reference.getQualifierExpression());
		Expression value = name("value");
		if (types().isList(type) || types().isDeque(type)) {
			return switch (name) {
				case "add" -> {
					if (method.getParameters().size() != 1) {
						throw unsupported(reference);
					}
					Expression slot = new Expression.Index(collection, LibraryCalls.nextIndex(collection));
					yield statementFunction(List.of("value"), new Statement.Assignment(List.of(slot), List.of(value)));
				}
				case "contains" -> {
					unit().use(Helper.LIST_CONTAINS);
					yield function(List.of("value"), Helper.LIST_CONTAINS.invoke(collection, value), false);
				}
				default -> throw unsupported(reference);
			};
		}
		if (types().isSet(type)) {
			return switch (name) {
				case "add" ->
						statementFunction(List.of("value"),
								new Statement.Assignment(List.of(new Expression.Index(collection, value)),
								List.of(new Expression.BooleanLiteral(true))));
				case "contains" -> function(List.of("value"), binary(new Expression.Index(collection, value),
						BinaryOperator.NOT_EQUAL, new Expression.Nil()), false);
				default -> throw unsupported(reference);
			};
		}
		if (types().isMap(type)) {
			return switch (name) {
				case "get" -> function(List.of("key"), new Expression.Index(collection, name("key")), false);
				case "containsKey" -> function(List.of("key"), binary(new Expression.Index(collection, name("key")),
						BinaryOperator.NOT_EQUAL, new Expression.Nil()), false);
				default -> throw unsupported(reference);
			};
		}
		throw unsupported(reference);
	}

	/** The object a bound reference calls its method on, which must read the same each time the function runs. */
	private Expression boundReceiver(MemberReferenceTree reference) {
		ExpressionTree qualifier = reference.getQualifierExpression();
		if (!(qualifier instanceof IdentifierTree) && !(qualifier instanceof MemberSelectTree)) {
			throw new Rejection(qualifier, "Java reads this value once, where the reference is, but Lua's function"
					+ " would read it each time it runs; store it in a local first.");
		}
		return body.expression(qualifier);
	}

	/**
	 * The function's parameters, named as the method names them; one that would hide the name of the object a bound
	 * reference calls gets a name of its own.
	 */
	List<String> parameters(ExecutableElement method, MemberReferenceTree where) {
		String qualifier = where.getQualifierExpression() instanceof IdentifierTree identifier
				? unit().names.luaName(identifier.getName().toString())
				: "";
		return method.getParameters().stream()
				.map(parameter -> {
					String own = unit().names.luaName(parameter.getSimpleName().toString());
					return own.equals(qualifier) ? unit().freshLocal(own + "Value", where) : own;
				})
				.toList();
	}

	/** {@code function(a) return value end}, or for a method that returns nothing {@code function(a) call end}. */
	static Expression function(List<String> parameters, Expression value, boolean isVoid) {
		Statement statement = isVoid ? new Statement.CallStatement(value) : new Statement.Return(List.of(value));
		return new Expression.FunctionExpression(FunctionBody.of(parameters, Block.of(statement)));
	}

	private static Expression statementFunction(List<String> parameters, Statement statement) {
		return new Expression.FunctionExpression(FunctionBody.of(parameters, Block.of(statement)));
	}

	private boolean isTypeName(@Nullable ExpressionTree qualifier) {
		return qualifier != null && (qualifier instanceof IdentifierTree || qualifier instanceof MemberSelectTree)
				&& unit().element(qualifier) instanceof TypeElement;
	}

	private static boolean isSystemOut(ExpressionTree qualifier) {
		return qualifier instanceof MemberSelectTree select && select.getIdentifier().contentEquals("out")
				&& select.getExpression() instanceof IdentifierTree system && system.getName().contentEquals("System");
	}

	/** {@code herb} for {@code Herb}: the name of one value of the class. */
	static String decapitalized(Element type) {
		String name = type.getSimpleName().toString();
		return name.substring(0, 1).toLowerCase(Locale.ROOT) + name.substring(1);
	}

	private static Rejection unsupported(MemberReferenceTree reference) {
		return new Rejection(reference, "Oneiro has no Lua function for this method reference; write it as a lambda,"
				+ " x -> ..., which it lowers in place.");
	}
}
