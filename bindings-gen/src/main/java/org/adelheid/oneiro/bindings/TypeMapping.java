package org.adelheid.oneiro.bindings;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.adelheid.oneiro.bindings.JavaBindings.JType;
import org.adelheid.oneiro.bindings.JavaBindings.Node;

/**
 * The Java type of each LuaCATS type the stubs give: Lua's numbers, booleans and strings as Java's, arrays as
 * lists, tables of keys and values as maps, functions as {@code java.util.function} interfaces, the stubs' classes
 * and enums as their bindings, and anything Java cannot type more closely as {@code Object}.
 */
final class TypeMapping {

	private final JavaBindings bindings;
	private final ApiModel model;

	TypeMapping(JavaBindings bindings) {
		this.bindings = bindings;
		this.model = bindings.model;
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
			case TypeRef.Union _ -> JType.OBJECT;
			case TypeRef.Function function -> functional(function);
			case TypeRef.Table table -> table(table);
			case TypeRef.Literal _ -> JType.reference("String");
		};
	}

	private JType named(TypeRef.Named named) {
		String original = named.name();
		if (original.equals("Long") || original.equals("Uint64")) {
			return JType.primitive("long", "Long");
		}
		String name = model.resolveAlias(original);
		switch (name) {
			case "integer" -> {
				return JType.primitive("int", "Integer");
			}
			case "number" -> {
				return JType.primitive("double", "Double");
			}
			case "boolean" -> {
				return JType.primitive("boolean", "Boolean");
			}
			case "string" -> {
				return JType.reference("String");
			}
			case "nil" -> {
				return JType.VOID;
			}
			case "table" -> {
				return named.args().size() == 2
						? JType.reference("java.util.Map<" + javaType(named.args().get(0)).boxed() + ", "
								+ javaType(named.args().get(1)).boxed() + ">")
						: JType.OBJECT;
			}
			case "any", "unknown" -> {
				return JType.OBJECT;
			}
			case "function" -> {
				return JType.reference("Runnable");
			}
			default -> {
			}
		}
		ApiModel.ClassDef classDef = model.classes.get(name);
		if (classDef != null && "integer".equals(classDef.superclass)) {
			return JType.primitive("int", "Integer");
		}
		Node node = bindings.byLuaName.get(name);
		if (node == null) {
			bindings.warnings.add("The type " + original + " has no binding class; Object stands in for it.");
			return JType.OBJECT;
		}
		ApiModel.EnumDef enumDef = node.enumDef;
		if (enumDef != null && bindings.typeUses.getOrDefault(enumDef.name(), 0) == 0) {
			return enumDef.integerValued() ? JType.primitive("int", "Integer") : JType.reference("String");
		}
		return JType.reference(node.reference());
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
				bindings.warnings.add("The function type " + function.show() + " has more parameters than"
						+ " java.util.function covers; Object stands in for it.");
				yield JType.OBJECT;
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
				return JType.reference("java.util.Map<" + javaType(key).boxed() + ", " + javaType(entry.value()).boxed()
						+ ">");
			}
		}
		return JType.OBJECT;
	}
}
