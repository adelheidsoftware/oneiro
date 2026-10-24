package org.adelheid.oneiro.java;

import static org.adelheid.oneiro.lua.Expression.binary;
import static org.adelheid.oneiro.lua.Expression.call;
import static org.adelheid.oneiro.lua.Expression.field;
import static org.adelheid.oneiro.lua.Expression.integer;
import static org.adelheid.oneiro.lua.Expression.name;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.adelheid.oneiro.lua.BinaryOperator;
import org.adelheid.oneiro.lua.Block;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.LuaNames;
import org.adelheid.oneiro.lua.Statement;
import org.jspecify.annotations.Nullable;

/**
 * The methods of lists, maps, sets, queues and deques on the Lua tables that hold them: a list is a sequence that
 * counts from 1, a set a table of its elements as keys, and a map a table of keys to values. Also
 * {@code List.of}, {@code Map.of} and {@code Set.of}, sorting with Java's comparators, and copying one
 * collection into another.
 */
final class CollectionMethods {

	private final LibraryCalls library;
	private final BodyLowering body;
	private final UnitLowering unit;
	private final JavaTypes types;

	CollectionMethods(LibraryCalls library) {
		this.library = library;
		this.body = library.body;
		this.unit = body.unit;
		this.types = body.types();
	}

	Expression listMethod(MethodInvocationTree call, String name, ExpressionTree receiver,
			List<? extends ExpressionTree> arguments) {
		Expression list = body.expression(receiver);
		return switch (name) {
			case "get" -> body.operators().listElement(list, arguments.getFirst());
			case "getFirst" -> new Expression.Index(list, integer(1));
			case "getLast" -> new Expression.Index(list, LibraryCalls.length(list));
			case "removeFirst" -> call(field(name("table"), "remove"), list, integer(1));
			case "removeLast" -> call(field(name("table"), "remove"), list);
			case "size" -> LibraryCalls.length(list);
			case "isEmpty" -> binary(LibraryCalls.length(list), BinaryOperator.EQUAL, integer(0));
			case "contains" -> {
				unit.use(Helper.LIST_CONTAINS);
				yield Helper.LIST_CONTAINS.invoke(list, body.expression(arguments.getFirst()));
			}
			case "indexOf" -> {
				unit.use(Helper.LIST_INDEX_OF);
				yield Helper.LIST_INDEX_OF.invoke(list, body.expression(arguments.getFirst()));
			}
			case "remove" -> {
				if (unit.type(arguments.getFirst()).getKind() != TypeKind.INT) {
					throw Rejection.notYet(call, "List.remove of an element rather than an index");
				}
				yield call(field(name("table"), "remove"), list,
						IndexArithmetic.plusOne(body.expression(arguments.getFirst())));
			}
			case "add" -> {
				if (arguments.size() != 2) {
					throw new Rejection(call, LibraryCalls.STATEMENT_ONLY.formatted("add"));
				}
				yield call(field(name("table"), "insert"), list,
						IndexArithmetic.plusOne(body.expression(arguments.getFirst())),
						library.element(arguments.get(1)));
			}
			case "set", "addAll", "forEach", "removeIf" ->
					throw new Rejection(call, LibraryCalls.STATEMENT_ONLY.formatted(name));
			case "sort" -> sort(list, arguments.getFirst());
			case "stream" -> throw new Rejection(call, LibraryCalls.STREAM_IN_STATEMENT);
			default -> {
				if (types.isDeque(unit.type(receiver))) {
					yield dequeMethod(call, name, list, arguments);
				}
				throw Rejection.notYet(call, "List." + name);
			}
		};
	}

	Expression mapMethod(MethodInvocationTree call, String name, ExpressionTree receiver,
			List<? extends ExpressionTree> arguments) {
		Expression map = body.expression(receiver);
		return switch (name) {
			case "get" -> {
				library.checkKey(arguments.getFirst());
				yield new Expression.Index(map, body.expression(arguments.getFirst()));
			}
			case "getOrDefault" -> {
				library.checkKey(arguments.getFirst());
				Expression stored = new Expression.Index(map, body.expression(arguments.getFirst()));
				yield library.orDefault(stored, types.isBoolean(types.typeArgument(unit.type(receiver), 1)),
						arguments.get(1));
			}
			case "computeIfAbsent" -> {
				library.checkKey(arguments.getFirst());
				yield library.mapDefault(Helper.COMPUTE_IF_ABSENT, receiver, arguments);
			}
			case "compute" -> {
				library.checkKey(arguments.getFirst());
				yield library.mapDefault(Helper.COMPUTE, receiver, arguments);
			}
			case "computeIfPresent" -> {
				library.checkKey(arguments.getFirst());
				yield library.mapDefault(Helper.COMPUTE_IF_PRESENT, receiver, arguments);
			}
			case "merge" -> {
				library.checkKey(arguments.getFirst());
				yield library.mapDefault(Helper.MERGE, receiver, arguments);
			}
			case "containsKey" -> {
				library.checkKey(arguments.getFirst());
				yield binary(new Expression.Index(map, body.expression(arguments.getFirst())),
						BinaryOperator.NOT_EQUAL, new Expression.Nil());
			}
			case "size" -> {
				unit.use(Helper.COUNT);
				yield Helper.COUNT.invoke(map);
			}
			case "isEmpty" -> binary(call(name("next"), map), BinaryOperator.EQUAL, new Expression.Nil());
			case "put", "remove", "putIfAbsent", "forEach" ->
					throw new Rejection(call, LibraryCalls.STATEMENT_ONLY.formatted(name));
			case "keySet", "values", "entrySet" ->
					throw Rejection.notYet(call, "Map." + name + " outside a for-each loop");
			case "firstKey" -> {
				unit.use(Helper.FIRST_KEY);
				yield Helper.FIRST_KEY.invoke(map);
			}
			case "lastKey" -> {
				unit.use(Helper.LAST_KEY);
				yield Helper.LAST_KEY.invoke(map);
			}
			default -> throw Rejection.notYet(call, "Map." + name);
		};
	}

	Expression setMethod(MethodInvocationTree call, String name, ExpressionTree receiver,
			List<? extends ExpressionTree> arguments) {
		Expression set = body.expression(receiver);
		return switch (name) {
			case "contains" -> {
				library.checkKey(arguments.getFirst());
				yield binary(new Expression.Index(set, body.expression(arguments.getFirst())),
						BinaryOperator.NOT_EQUAL, new Expression.Nil());
			}
			case "size" -> {
				unit.use(Helper.COUNT);
				yield Helper.COUNT.invoke(set);
			}
			case "isEmpty" -> binary(call(name("next"), set), BinaryOperator.EQUAL, new Expression.Nil());
			case "stream" -> throw new Rejection(call, LibraryCalls.STREAM_IN_STATEMENT);
			case "add", "remove", "forEach", "removeIf", "addAll" ->
					throw new Rejection(call, LibraryCalls.STATEMENT_ONLY.formatted(name));
			case "first", "getFirst" -> {
				unit.use(Helper.FIRST_KEY);
				yield Helper.FIRST_KEY.invoke(set);
			}
			case "last", "getLast" -> {
				unit.use(Helper.LAST_KEY);
				yield Helper.LAST_KEY.invoke(set);
			}
			default -> throw Rejection.notYet(call, "Set." + name);
		};
	}

	/**
	 * A queue's or deque's method on its Lua list, whose first element is the front: {@code poll()} removes it,
	 * {@code peek()} reads it. An empty queue gives nil, where Java's {@code remove()} and {@code element()} would
	 * throw.
	 */
	Expression dequeMethod(MethodInvocationTree call, String name, Expression queue,
			List<? extends ExpressionTree> arguments) {
		return switch (name) {
			case "poll", "pollFirst", "pop", "removeFirst", "remove" -> {
				if (!arguments.isEmpty()) {
					throw Rejection.notYet(call, "Removing a given element from a queue");
				}
				yield call(field(name("table"), "remove"), queue, integer(1));
			}
			case "pollLast", "removeLast" -> call(field(name("table"), "remove"), queue);
			case "peek", "peekFirst", "getFirst", "element" -> new Expression.Index(queue, integer(1));
			case "peekLast", "getLast" -> new Expression.Index(queue, LibraryCalls.length(queue));
			case "size" -> LibraryCalls.length(queue);
			case "isEmpty" -> binary(LibraryCalls.length(queue), BinaryOperator.EQUAL, integer(0));
			case "contains" -> {
				unit.use(Helper.LIST_CONTAINS);
				yield Helper.LIST_CONTAINS.invoke(queue, body.expression(arguments.getFirst()));
			}
			case "add", "addFirst", "addLast", "offer", "offerFirst", "offerLast", "push", "forEach", "removeIf" ->
					throw new Rejection(call, LibraryCalls.STATEMENT_ONLY.formatted(name));
			default -> throw Rejection.notYet(call, "Queue." + name);
		};
	}

	/**
	 * {@code table.sort} with Java's comparator turned into Lua's "comes before" function; see {@link Comparators}.
	 * Lua's sort is not stable, unlike Java's.
	 */
	Expression sort(Expression list, @Nullable ExpressionTree comparator) {
		if (comparator == null || (comparator instanceof LiteralTree literal && literal.getValue() == null)) {
			return call(field(name("table"), "sort"), list);
		}
		return call(field(name("table"), "sort"), list, body.comparators().function(comparator));
	}

	Expression mapOf(List<? extends ExpressionTree> arguments) {
		List<Expression.TableField> fields = new ArrayList<>();
		for (int index = 0; index + 1 < arguments.size(); index += 2) {
			fields.add(entry(arguments.get(index), library.element(arguments.get(index + 1))));
		}
		return new Expression.Table(fields);
	}

	Expression setOf(List<? extends ExpressionTree> elements) {
		return new Expression.Table(elements.stream()
				.map(element -> entry(element, new Expression.BooleanLiteral(true)))
				.toList());
	}

	/** {@code name = value} for a string key that is a Lua name, otherwise {@code [key] = value}. */
	private Expression.TableField entry(ExpressionTree key, Expression value) {
		library.checkKey(key);
		if (key instanceof LiteralTree literal && literal.getValue() instanceof String text && LuaNames.isName(text)) {
			return new Expression.TableField.Named(text, value);
		}
		return new Expression.TableField.Keyed(body.expression(key), value);
	}

	/** What a collection holds, for copying it: a list's elements, a set's keys, a map's values or entries. */
	private enum Contents {
		ELEMENTS,
		KEYS,
		VALUES,
		ENTRIES
	}

	/** What a collection argument holds, and the tree of the Lua table that holds it: a map for its views. */
	private record Holding(Contents contents, ExpressionTree table) {
	}

	private Holding holding(ExpressionTree source) {
		TypeMirror type = unit.type(source);
		if (types.isList(type) || types.isDeque(type) || type.getKind() == TypeKind.ARRAY) {
			return new Holding(Contents.ELEMENTS, source);
		}
		if (source instanceof MethodInvocationTree view && view.getArguments().isEmpty()
				&& view.getMethodSelect() instanceof MemberSelectTree select
				&& types.isMap(unit.type(select.getExpression()))) {
			switch (select.getIdentifier().toString()) {
				case "keySet" -> {
					return new Holding(Contents.KEYS, select.getExpression());
				}
				case "values" -> {
					return new Holding(Contents.VALUES, select.getExpression());
				}
				default -> {
				}
			}
		}
		if (types.isSet(type)) {
			return new Holding(Contents.KEYS, source);
		}
		if (types.isMap(type)) {
			return new Holding(Contents.ENTRIES, source);
		}
		throw Rejection.notYet(source, "Copying a " + LibraryCalls.simpleName(type));
	}

	/** {@code new ArrayList<>(other)}: a list's elements moved into a new table, or a set's keys or map's values. */
	Expression listCopy(NewClassTree creation, ExpressionTree source) {
		Holding holding = holding(source);
		return switch (holding.contents()) {
			case ELEMENTS -> {
				Expression list = library.stableReceiver(source);
				yield call(field(name("table"), "move"), list, integer(1), LibraryCalls.length(list), integer(1),
						new Expression.Table(List.of()));
			}
			case KEYS -> {
				unit.use(Helper.KEYS_OF);
				yield Helper.KEYS_OF.invoke(body.expression(holding.table()));
			}
			case VALUES -> {
				unit.use(Helper.VALUES_OF);
				yield Helper.VALUES_OF.invoke(body.expression(holding.table()));
			}
			case ENTRIES -> throw Rejection.notYet(creation, "A list of a map's entries");
		};
	}

	/** A new set or map filled from another collection, through the helper that fits what it holds. */
	Expression filled(NewClassTree creation, Expression empty, boolean set, ExpressionTree source) {
		Holding holding = holding(source);
		Expression table = body.expression(holding.table());
		Helper helper;
		if (set && holding.contents() == Contents.ELEMENTS) {
			helper = Helper.ADD_ELEMENTS;
		} else if (set && holding.contents() == Contents.KEYS) {
			helper = Helper.ADD_KEYS;
		} else if (!set && holding.contents() == Contents.ENTRIES) {
			helper = Helper.COPY_ENTRIES;
		} else {
			throw Rejection.notYet(creation, "Filling a " + (set ? "set" : "map") + " from a "
					+ LibraryCalls.simpleName(unit.type(source)));
		}
		unit.use(helper);
		return helper.invoke(empty, table);
	}

	/**
	 * {@code list.addAll(set)}, {@code set.addAll(list)} or {@code map.putAll(other)} as a loop over what the other
	 * collection holds; a list into a list is {@code table.move}.
	 */
	List<Statement> addedAll(ExpressionTree receiver, String target, ExpressionTree source) {
		Holding holding = holding(source);
		Expression into = library.stableReceiver(receiver);
		if (target.equals("list") && holding.contents() == Contents.ELEMENTS) {
			Expression list = library.stableReceiver(source);
			Expression moved = call(field(name("table"), "move"), list, integer(1), LibraryCalls.length(list),
					LibraryCalls.nextIndex(into), into);
			return List.of(new Statement.CallStatement(moved));
		}
		Expression table = body.expression(holding.table());
		String key = unit.freshLocal("key", source);
		String value = unit.freshLocal("value", source);
		Statement stored = switch (target + " " + holding.contents()) {
			case "list KEYS" ->
					Statement.assignment(new Expression.Index(into, LibraryCalls.nextIndex(into)), name(key));
			case "list VALUES" ->
					Statement.assignment(new Expression.Index(into, LibraryCalls.nextIndex(into)), name(value));
			case "set ELEMENTS" ->
					Statement.assignment(new Expression.Index(into, name(value)), new Expression.BooleanLiteral(true));
			case "set KEYS" ->
					Statement.assignment(new Expression.Index(into, name(key)), new Expression.BooleanLiteral(true));
			case "map ENTRIES" -> Statement.assignment(new Expression.Index(into, name(key)), name(value));
			default -> throw Rejection.notYet(source, "Adding a " + LibraryCalls.simpleName(unit.type(source))
					+ " to a " + target);
		};
		List<String> names = switch (holding.contents()) {
			case ELEMENTS, VALUES -> List.of("_", value);
			case KEYS -> List.of(key);
			case ENTRIES -> List.of(key, value);
		};
		String iterator = holding.contents() == Contents.ELEMENTS ? "ipairs" : "pairs";
		return List.of(new Statement.GenericFor(names, List.of(call(name(iterator), table)), Block.of(stored)));
	}
}
