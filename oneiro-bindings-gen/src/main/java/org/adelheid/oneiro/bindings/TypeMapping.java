package org.adelheid.oneiro.bindings;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.adelheid.oneiro.bindings.JavaBindings.JType;
import org.adelheid.oneiro.bindings.JavaBindings.Node;
import org.jspecify.annotations.Nullable;

/**
 * Chooses the Java type of each LuaCATS type, as {@link #javaType} lists: the model's classes and enums become their
 * bindings, a name the model does not declare becomes the class of the bindings that do, the RS3 API's first, and
 * anything Java cannot type more closely becomes {@code Object}.
 */
final class TypeMapping {

	private final JavaBindings bindings;
	private final ApiModel model;
	/** What the member being written can name. */
	private Scope scope = Scope.NONE;
	/** Every type variable the model's classes and functions declare, which elsewhere is an Object. */
	private @Nullable Set<String> declaredVariables;

	/**
	 * What a member can name: the type variables of its class, for an instance member, and its own, and the class that
	 * {@code self} stands for.
	 */
	record Scope(Set<String> variables, ApiModel.@Nullable ClassDef owner) {

		static final Scope NONE = new Scope(Set.of(), null);

		Scope {
			variables = Set.copyOf(variables);
		}
	}

	TypeMapping(JavaBindings bindings) {
		this.bindings = bindings;
		this.model = bindings.model;
	}

	/** Sets what the member being written can name, and returns what it replaces, for the caller to put back. */
	Scope scope(Scope next) {
		Scope previous = scope;
		scope = next;
		return previous;
	}

	/**
	 * The Java type of a LuaCATS type: Lua's numbers, booleans and strings as Java's; {@code T[]} as {@code List<T>};
	 * {@code { [K]: V }} and {@code table<K, V>} as {@code Map<K, V>}; a tuple as a list; functions as
	 * {@code java.util.function} interfaces; {@code table}, {@code any} and unions as {@code Object}; nullable types
	 * annotated {@code @Nullable}.
	 */
	JType javaType(TypeRef type) {
		if (type instanceof TypeRef.Named named && named.name().equals("nil")) {
			return JType.VOID;
		}
		if (type.isNullable()) {
			JType inner = javaType(type.withoutNil());
			return inner.text().equals("void") ? JType.OBJECT : inner.nullable();
		}
		return switch (type) {
			case TypeRef.Named named -> named(named);
			case TypeRef.ArrayOf array -> JType.reference("java.util.List<" + javaType(array.element()).boxed() + ">");
			case TypeRef.Optional optional -> javaType(optional.inner()).nullable();
			case TypeRef.Union union -> literals(union);
			case TypeRef.Function function -> functional(function);
			case TypeRef.Table table -> table(table);
			case TypeRef.Literal _ -> JType.reference("String");
			case TypeRef.NumberLiteral literal -> literal.isInteger()
					? JType.primitive("int", "Integer")
					: JType.primitive("double", "Double");
		};
	}

	private JType named(TypeRef.Named named) {
		String original = named.name();
		if (original.equals("Long") || original.equals("Uint64")) {
			return JType.primitive("long", "Long");
		}
		if (scope.variables().contains(original)) {
			return JType.reference(original);
		}
		ApiModel.ClassDef owner = scope.owner();
		if (original.equals("self") && owner != null) {
			// ---@return self: the object itself, of its own class, as a fluent method returns it
			List<TypeRef> variables = owner.typeParams.stream()
					.filter(scope.variables()::contains)
					.map(variable -> (TypeRef) TypeRef.Named.of(variable))
					.toList();
			JType self = binding(bindings, owner.name, variables);
			if (self != null) {
				return self;
			}
		}
		String name = model.resolveAlias(original);
		JType builtin = builtin(model, name, named);
		if (builtin != null) {
			return builtin;
		}
		JType own = binding(bindings, name, named.args());
		if (own != null) {
			return own;
		}
		// A type the model names but does not declare: the RS3 API's ui.Component in a library's types
		for (JavaBindings other : bindings.foreign) {
			String resolved = other.model.resolveAlias(name);
			JType foreignBuiltin = builtin(other.model, resolved, named);
			if (foreignBuiltin != null) {
				return foreignBuiltin;
			}
			JType found = binding(other, resolved, named.args());
			if (found != null) {
				return found;
			}
		}
		if (declaredVariables().contains(original)) {
			// A class's type variable in a static member, which cannot name it
			return JType.OBJECT;
		}
		bindings.warnings.add("The type " + original + " has no binding class; Object stands in for it.");
		return JType.OBJECT;
	}

	private Set<String> declaredVariables() {
		Set<String> declared = declaredVariables;
		if (declared == null) {
			declared = new HashSet<>();
			for (ApiModel.ClassDef classDef : model.classes.values()) {
				declared.addAll(classDef.typeParams);
			}
			for (ApiModel.FunctionDef function : model.allFunctions()) {
				declared.addAll(function.typeParams());
			}
			declaredVariables = declared;
		}
		return declared;
	}

	/**
	 * A union: of string literals, {@code "left" | "right"}, a string; of integer literals, an int; of anything else,
	 * an Object.
	 */
	private static JType literals(TypeRef.Union union) {
		if (union.options().stream().allMatch(TypeRef.Literal.class::isInstance)) {
			return JType.reference("String");
		}
		if (union.options().stream().allMatch(option -> option instanceof TypeRef.NumberLiteral number
				&& number.isInteger())) {
			return JType.primitive("int", "Integer");
		}
		return JType.OBJECT;
	}

	/**
	 * Lua's own types and the names LuaCATS gives them, and an alias of a type that is no name, such as a union of
	 * literals.
	 */
	private @Nullable JType builtin(ApiModel of, String name, TypeRef.Named named) {
		ApiModel.AliasDef alias = of.aliases.get(name);
		if (alias != null && !(alias.target() instanceof TypeRef.Named)) {
			return javaType(alias.target());
		}
		return switch (name) {
			case "integer" -> JType.primitive("int", "Integer");
			case "number" -> JType.primitive("double", "Double");
			case "boolean" -> JType.primitive("boolean", "Boolean");
			case "string" -> JType.reference("String");
			case "nil" -> JType.VOID;
			case "table" -> named.args().size() == 2 ? map(named.args().get(0), named.args().get(1)) : JType.OBJECT;
			case "any", "unknown", "userdata", "lightuserdata", "thread" -> JType.OBJECT;
			case "function" -> JType.reference("Runnable");
			default -> null;
		};
	}

	/**
	 * The binding of a class or enum of one set of bindings, with its package for another set's and for the plugin's
	 * own Lua, and with the type arguments a generic class takes; null when that set declares none of the name.
	 */
	private @Nullable JType binding(JavaBindings of, String name, List<TypeRef> args) {
		ApiModel.ClassDef classDef = of.model.classes.get(name);
		if (classDef != null && "integer".equals(classDef.superclass)) {
			return JType.primitive("int", "Integer");
		}
		Node node = of.byLuaName.get(name);
		if (node == null) {
			return null;
		}
		ApiModel.EnumDef enumDef = node.enumDef;
		if (enumDef != null && enumDef.keys()) {
			// ---@enum (key): a value of the type is one of the table's keys
			return JType.reference("String");
		}
		if (enumDef != null && of.typeUses.getOrDefault(enumDef.name(), 0) == 0) {
			return enumDef.integerValued() ? JType.primitive("int", "Integer") : JType.reference("String");
		}
		String reference = of == bindings ? of.qualified(node) : of.packageOf(node.top()) + "." + node.reference();
		ApiModel.ClassDef nodeClass = node.classDef;
		if (nodeClass != null && !nodeClass.typeParams.isEmpty() && nodeClass.typeParams.size() == args.size()) {
			// Event<LogicEventPayload, nil>: nil is no value, Void in Java, whose one value is null
			String arguments = args.stream()
					.map(arg -> arg instanceof TypeRef.Named nil && nil.name().equals("nil")
							? "@Nullable Void"
							: javaType(arg).boxed())
					.collect(Collectors.joining(", "));
			return JType.reference(reference + "<" + arguments + ">");
		}
		return JType.reference(reference);
	}

	private JType functional(TypeRef.Function function) {
		List<TypeRef.Param> params = function.params().stream().filter(param -> !param.name().equals("...")).toList();
		TypeRef returned = function.returns().isEmpty() ? null : function.returns().getFirst();
		boolean returnsNothing = returned == null
				|| (returned instanceof TypeRef.Named named && named.name().equals("nil"));
		boolean returnsBoolean = returned != null && returned.withoutNil() instanceof TypeRef.Named named
				&& model.resolveAlias(named.name()).equals("boolean");
		String result = returned == null || returnsNothing ? "" : javaType(returned).boxed();
		String joined = params.stream().map(param -> javaType(param.type()).boxed()).collect(Collectors.joining(", "));
		String prefix = "java.util.function.";
		return switch (params.size()) {
			case 0 -> JType.reference(returnsNothing ? "Runnable"
					: returnsBoolean ? prefix + "BooleanSupplier"
					: prefix + "Supplier<" + result + ">");
			case 1 -> JType.reference(returnsNothing ? prefix + "Consumer<" + joined + ">"
					: returnsBoolean ? prefix + "Predicate<" + joined + ">"
					: prefix + "Function<" + joined + ", " + result + ">");
			case 2 -> JType.reference(returnsNothing ? prefix + "BiConsumer<" + joined + ">"
					: returnsBoolean ? prefix + "BiPredicate<" + joined + ">"
					: prefix + "BiFunction<" + joined + ", " + result + ">");
			default -> {
				// More values than java.util.function's interfaces take: an interface of the bindings' own, in the
				// target's package, which the plugin's own Lua's classes in other packages import
				String name = (returnsNothing ? "Consumer" : "Function") + params.size();
				bindings.callbacks.add(name);
				String reference = bindings.target.isPlugin() ? bindings.target.packageName() + "." + name : name;
				yield JType.reference(reference + "<" + joined + (returnsNothing ? "" : ", " + result) + ">");
			}
		};
	}

	private JType table(TypeRef.Table table) {
		if (table.isTuple()) {
			Set<String> types = table.entries().stream()
					.map(entry -> javaType(entry.value()).boxed())
					.collect(Collectors.toCollection(LinkedHashSet::new));
			return JType.reference("java.util.List<" + (types.size() == 1 ? types.iterator().next() : "Object") + ">");
		}
		if (table.entries().size() == 1) {
			TypeRef.Entry entry = table.entries().getFirst();
			TypeRef key = entry.keyType();
			if (key != null) {
				return map(key, entry.value());
			}
		}
		return JType.OBJECT;
	}

	private JType map(TypeRef key, TypeRef value) {
		return JType.reference("java.util.Map<" + javaType(key).boxed() + ", " + javaType(value).boxed() + ">");
	}
}
