package org.adelheid.oneiro.bindings;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * A parsed LuaCATS type expression, such as {@code Int?}, {@code CoordGrid[]}, {@code Bool|Int|nil},
 * {@code EventSubscription<LogicEventPayload, nil>} or {@code fun(self: HTTPRequest)?}.
 */
sealed interface TypeRef {

	/** A type name, possibly dotted ({@code ui.Component}) and possibly with type arguments. */
	record Named(String name, List<TypeRef> args) implements TypeRef {

		public Named {
			args = List.copyOf(args);
		}

		static Named of(String name) {
			return new Named(name, List.of());
		}
	}

	/** {@code T[]}: a Lua array. */
	record ArrayOf(TypeRef element) implements TypeRef {
	}

	/** {@code T?}: the value may be nil (for a parameter: it may be omitted). */
	record Optional(TypeRef inner) implements TypeRef {
	}

	/** {@code A|B|C}. */
	record Union(List<TypeRef> options) implements TypeRef {

		public Union {
			options = List.copyOf(options);
		}
	}

	/** {@code fun(a: A, b: B): R}. */
	record Function(List<Param> params, List<TypeRef> returns) implements TypeRef {

		public Function {
			params = List.copyOf(params);
			returns = List.copyOf(returns);
		}
	}

	/** {@code { [string]: X, FromID: fun(id: Y): X }}: a table shape; {@code {[1]: Int, [2]: ObjType}} is a tuple. */
	record Table(List<Entry> entries) implements TypeRef {

		public Table {
			entries = List.copyOf(entries);
		}

		/** Whether every key is a position, {@code [1]}, {@code [2]}: a fixed-size tuple. */
		boolean isTuple() {
			return !entries.isEmpty() && entries.stream().allMatch(entry -> entry.name().startsWith("["));
		}
	}

	/** A string literal type such as {@code "INT"}. */
	record Literal(String text) implements TypeRef {
	}

	/** One parameter of a {@link Function} type; {@code name} is empty when the stub gives only a type. */
	record Param(String name, TypeRef type) {
	}

	/**
	 * One entry of a {@link Table} type: {@code [keyType]: value}, with an empty name, or {@code name: value}, with a
	 * null key type; a tuple position is named {@code [1]}.
	 */
	record Entry(String name, @Nullable TypeRef keyType, TypeRef value) {
	}

	/** Whether nil is an allowed value: {@code T?}, or a union containing {@code nil}. */
	default boolean isNullable() {
		return switch (this) {
			case Optional _ -> true;
			case Union union -> union.options().stream().anyMatch(TypeRef::isNullable);
			case Named named -> named.name().equals("nil");
			default -> false;
		};
	}

	/** The type without {@code ?} and without {@code nil} in a union; a union of one other type is that type. */
	default TypeRef withoutNil() {
		return switch (this) {
			case Optional optional -> optional.inner().withoutNil();
			case Union union -> {
				List<TypeRef> rest = union.options().stream()
						.filter(option -> !(option instanceof Named named && named.name().equals("nil")))
						.map(TypeRef::withoutNil)
						.toList();
				yield rest.size() == 1 ? rest.getFirst() : new Union(rest);
			}
			default -> this;
		};
	}

	/** The types directly nested in this one. */
	default List<TypeRef> children() {
		return switch (this) {
			case Named named -> named.args();
			case ArrayOf array -> List.of(array.element());
			case Optional optional -> List.of(optional.inner());
			case Union union -> union.options();
			case Function function -> Stream.concat(
					function.params().stream().map(Param::type), function.returns().stream()).toList();
			case Table table -> table.entries().stream()
					.flatMap(entry -> {
						TypeRef key = entry.keyType();
						return key == null ? Stream.of(entry.value()) : Stream.of(key, entry.value());
					})
					.toList();
			case Literal _ -> List.of();
		};
	}

	/** The type written back in LuaCATS syntax, for messages and Javadoc. */
	default String show() {
		return switch (this) {
			case Named named -> named.args().isEmpty()
					? named.name()
					: named.name() + "<" + shown(named.args(), ", ") + ">";
			case ArrayOf array -> array.element().show() + "[]";
			case Optional optional -> optional.inner().show() + "?";
			case Union union -> shown(union.options(), "|");
			case Function function -> "fun("
					+ function.params().stream()
							.map(param -> param.name().isEmpty()
									? param.type().show()
									: param.name() + ": " + param.type().show())
							.collect(Collectors.joining(", "))
					+ ")"
					+ (function.returns().isEmpty() ? "" : ": " + shown(function.returns(), ", "));
			case Table table -> "{ " + table.entries().stream()
					.map(entry -> {
						TypeRef key = entry.keyType();
						return (key != null ? "[" + key.show() + "]" : entry.name()) + ": " + entry.value().show();
					})
					.collect(Collectors.joining(", ")) + " }";
			case Literal literal -> "\"" + literal.text() + "\"";
		};
	}

	private static String shown(List<TypeRef> types, String separator) {
		return types.stream().map(TypeRef::show).collect(Collectors.joining(separator));
	}
}
