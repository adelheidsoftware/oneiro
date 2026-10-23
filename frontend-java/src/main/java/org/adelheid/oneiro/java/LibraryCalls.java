package org.adelheid.oneiro.java;

import static org.adelheid.oneiro.lua.Expression.binary;
import static org.adelheid.oneiro.lua.Expression.call;
import static org.adelheid.oneiro.lua.Expression.field;
import static org.adelheid.oneiro.lua.Expression.integer;
import static org.adelheid.oneiro.lua.Expression.methodCall;
import static org.adelheid.oneiro.lua.Expression.name;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.adelheid.oneiro.lua.BinaryOperator;
import org.adelheid.oneiro.lua.Block;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.FunctionBody;
import org.adelheid.oneiro.lua.LuaNames;
import org.adelheid.oneiro.lua.Statement;
import org.adelheid.oneiro.lua.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * The JDK classes a plugin may use and their Lua forms, as listed in docs/java-subset.md: {@code Math}, {@code String},
 * the boxed numbers, {@code List}, {@code Map}, {@code Set}, {@code StringBuilder} and {@code System.out}. Lists, maps
 * and sets are plain Lua tables, so they pass to and from the RS3 API unchanged. Anything else is rejected as not
 * supported yet.
 */
final class LibraryCalls {

	private static final String STATEMENT_ONLY = "In Lua %s is a statement, not a value; call it on its own line.";
	private static final String NO_ITERATOR = "Lua walks tables with its for loop, not iterator objects; loop over the"
			+ " collection itself, or use removeIf to remove while walking.";
	static final String NO_CHAR_STREAM = "A Lua string has no stream of its characters; loop over"
			+ " text.toCharArray(), which walks the text itself.";
	private static final String STREAM_IN_STATEMENT = "In Lua a stream is a loop, which runs as statements; make the"
			+ " stream the whole value of a local, an assignment or a return, or a forEach statement of its own.";

	private final BodyLowering body;

	LibraryCalls(BodyLowering body) {
		this.body = body;
	}

	private JavaTypes types() {
		return body.types();
	}

	private UnitLowering unit() {
		return body.unit;
	}

	/**
	 * The statements for a call whose Lua form is a statement rather than an expression: {@code list.add(x)},
	 * {@code map.put(k, v)}, {@code set.add(x)} and each {@code append} of a {@code StringBuilder}. Null when the call
	 * has an expression form, which {@link #invocation} gives.
	 */
	@Nullable List<Statement> statements(MethodInvocationTree call) {
		if (!(call.getMethodSelect() instanceof MemberSelectTree select)) {
			return null;
		}
		String method = select.getIdentifier().toString();
		List<? extends ExpressionTree> arguments = call.getArguments();
		ExpressionTree receiverTree = select.getExpression();
		TypeMirror receiverType = unit().type(receiverTree);
		if (types().isStringBuilder(receiverType) && method.equals("append")) {
			return appends(call);
		}
		if (types().isStringBuilder(receiverType)) {
			List<Statement> edit = builderEdit(method, receiverTree, arguments);
			if (edit != null) {
				return edit;
			}
		}
		if (types().isOptional(receiverType) && (method.equals("ifPresent") || method.equals("ifPresentOrElse"))) {
			return ifPresent(receiverTree, arguments);
		}
		boolean collection = types().isList(receiverType) || types().isSet(receiverType) || types().isDeque(receiverType);
		if (method.equals("forEach") && arguments.size() == 1 && (collection || types().isMap(receiverType))) {
			return forEachLoop(receiverTree, receiverType, arguments.getFirst());
		}
		if (method.equals("removeIf") && arguments.size() == 1) {
			List<Statement> removal = removeIf(receiverTree, receiverType, arguments.getFirst());
			if (removal != null) {
				return removal;
			}
		}
		if (types().isDeque(receiverType)) {
			switch (method) {
				case "addFirst", "offerFirst", "push" -> {
					return List.of(new Statement.CallStatement(call(field(name("table"), "insert"),
							body.expression(receiverTree), integer(1), element(arguments.getFirst()))));
				}
				case "add", "addLast", "offer", "offerLast" -> {
					return List.of(appended(receiverTree, arguments.getFirst()));
				}
				default -> {
				}
			}
		}
		if (types().isList(receiverType)) {
			switch (method) {
				case "add" -> {
					if (arguments.size() == 1) {
						return List.of(appended(receiverTree, arguments.getFirst()));
					}
				}
				case "set" -> {
					Expression list = body.expression(receiverTree);
					return List.of(assign(body.listElement(list, arguments.getFirst()), element(arguments.get(1))));
				}
				case "addAll" -> {
					if (arguments.size() == 1) {
						return addedAll(receiverTree, "list", arguments.getFirst());
					}
				}
				case "addFirst" -> {
					return List.of(new Statement.CallStatement(call(field(name("table"), "insert"),
							body.expression(receiverTree), integer(1), element(arguments.getFirst()))));
				}
				case "addLast" -> {
					return List.of(appended(receiverTree, arguments.getFirst()));
				}
				default -> {
				}
			}
		}
		if (types().isMap(receiverType)) {
			switch (method) {
				case "put" -> {
					checkKey(arguments.getFirst());
					return List.of(assign(new Expression.Index(body.expression(receiverTree),
							body.expression(arguments.getFirst())), element(arguments.get(1))));
				}
				case "remove" -> {
					checkKey(arguments.getFirst());
					return List.of(assign(new Expression.Index(body.expression(receiverTree),
							body.expression(arguments.getFirst())), new Expression.Nil()));
				}
				case "putIfAbsent" -> {
					checkKey(arguments.getFirst());
					Expression stored = new Expression.Index(stableReceiver(receiverTree), stableReceiver(arguments.getFirst()));
					return List.of(new Statement.If(List.of(new Statement.If.Branch(
							binary(stored, BinaryOperator.EQUAL, new Expression.Nil()),
							Block.of(assign(stored, element(arguments.get(1)))))), null));
				}
				case "merge" -> {
					checkKey(arguments.getFirst());
					Expression sum = summed(receiverTree, arguments);
					if (sum != null) {
						Expression stored = new Expression.Index(stableReceiver(receiverTree),
								stableReceiver(arguments.getFirst()));
						return List.of(assign(stored, sum));
					}
					return List.of(new Statement.CallStatement(mapDefault(Helper.MERGE, receiverTree, arguments)));
				}
				case "computeIfAbsent", "compute", "computeIfPresent" -> {
					checkKey(arguments.getFirst());
					Helper helper = method.equals("compute") ? Helper.COMPUTE
							: method.equals("computeIfPresent") ? Helper.COMPUTE_IF_PRESENT
							: Helper.COMPUTE_IF_ABSENT;
					return List.of(new Statement.CallStatement(mapDefault(helper, receiverTree, arguments)));
				}
				case "putAll" -> {
					return addedAll(receiverTree, "map", arguments.getFirst());
				}
				default -> {
				}
			}
		}
		if (types().isSet(receiverType)) {
			switch (method) {
				case "add" -> {
					checkKey(arguments.getFirst());
					return List.of(assign(new Expression.Index(body.expression(receiverTree),
							element(arguments.getFirst())), new Expression.BooleanLiteral(true)));
				}
				case "remove" -> {
					checkKey(arguments.getFirst());
					return List.of(assign(new Expression.Index(body.expression(receiverTree),
							body.expression(arguments.getFirst())), new Expression.Nil()));
				}
				case "addAll" -> {
					return addedAll(receiverTree, "set", arguments.getFirst());
				}
				default -> {
				}
			}
		}
		return null;
	}

	/** {@code sb.append(a).append(b)} as one {@code sb[#sb + 1] = ...} line per appended value, in order. */
	private List<Statement> appends(MethodInvocationTree outermost) {
		List<ExpressionTree> values = new ArrayList<>();
		ExpressionTree current = outermost;
		while (current instanceof MethodInvocationTree call
				&& call.getMethodSelect() instanceof MemberSelectTree select
				&& select.getIdentifier().contentEquals("append")
				&& types().isStringBuilder(unit().type(select.getExpression()))) {
			if (call.getArguments().size() != 1) {
				throw Rejection.notYet(call, "StringBuilder.append with more than one argument");
			}
			values.addFirst(call.getArguments().getFirst());
			current = select.getExpression();
		}
		Expression builder = stableReceiver(current);
		List<Statement> statements = new ArrayList<>();
		for (ExpressionTree value : values) {
			statements.add(assign(new Expression.Index(builder, nextIndex(builder)), body.strings().asText(value)));
		}
		return statements;
	}

	/** {@code list[#list + 1] = x}, or {@code table.insert(list, x)} for a list from a call, which Lua must not make twice. */
	private Statement appended(ExpressionTree receiver, ExpressionTree value) {
		if (receiver instanceof MethodInvocationTree || receiver instanceof NewClassTree) {
			return new Statement.CallStatement(call(field(name("table"), "insert"), body.expression(receiver),
					element(value)));
		}
		Expression list = body.expression(receiver);
		return assign(new Expression.Index(list, nextIndex(list)), element(value));
	}

	/**
	 * {@code list.forEach(x -> ...)} as the loop a for-each statement would be: the lambda's code is the loop's body and
	 * its parameters the loop's variables; any other function is called for each element.
	 */
	private List<Statement> forEachLoop(ExpressionTree receiver, TypeMirror receiverType, ExpressionTree function) {
		boolean keyed = types().isMap(receiverType) || types().isSet(receiverType);
		Expression iterator = call(name(keyed ? "pairs" : "ipairs"), body.expression(receiver));
		List<String> names = new ArrayList<>();
		if (!keyed) {
			names.add("_");
		}
		BodyLowering.InlinedLambda inlined = body.inline(function);
		if (inlined != null) {
			names.addAll(inlined.parameters());
			return List.of(new Statement.GenericFor(names, List.of(iterator), inlined.body()));
		}
		List<String> values = types().isMap(receiverType)
				? List.of(unit().freshLocal("key", function), unit().freshLocal("value", function))
				: List.of(unit().freshLocal("item", function));
		names.addAll(values);
		List<Expression> arguments = values.stream().map(value -> (Expression) name(value)).toList();
		Expression action = body.expression(function);
		// A method reference's small function, opened in the loop: list::add is list[#list + 1] = item
		Statement opened = Inlining.statement(action, arguments);
		if (opened != null) {
			return List.of(new Statement.GenericFor(names, List.of(iterator), Block.of(opened)));
		}
		List<Statement> lowered = new ArrayList<>();
		Expression called = callable(action, function, lowered);
		lowered.add(new Statement.GenericFor(names, List.of(iterator), Block.of(new Statement.CallStatement(
				new Expression.Call(called, arguments)))));
		return lowered;
	}

	/**
	 * {@code removeIf(x -> test)}: on a list, a loop from the end, which removes as it goes without skipping an
	 * element; on a set, or a map's {@code keySet()} or {@code values()}, a loop that clears the entries that pass.
	 * Null for anything else.
	 */
	private @Nullable List<Statement> removeIf(ExpressionTree receiver, TypeMirror receiverType, ExpressionTree test) {
		List<Statement> lowered = new ArrayList<>();
		BodyLowering.InlinedLambda inlined = body.inline(test);
		if (inlined != null && inlined.value() == null) {
			throw new Rejection(test, "removeIf's test is a condition; write it as an expression, x -> condition.");
		}
		Expression action = inlined == null ? callable(test, lowered) : null;
		if (types().isList(receiverType) || types().isDeque(receiverType)) {
			Expression list = stableReceiver(receiver);
			String index = unit().freshLocal("i", receiver);
			String item = inlined != null ? inlined.parameters().getFirst() : unit().freshLocal("item", receiver);
			Expression condition = condition(inlined, action, item);
			lowered.add(new Statement.NumericFor(index, length(list), integer(1), integer(-1), Block.of(
					new Statement.Local(List.of(item), List.of(new Expression.Index(list, name(index)))),
					new Statement.If(List.of(new Statement.If.Branch(condition, Block.of(new Statement.CallStatement(
							call(field(name("table"), "remove"), list, name(index)))))), null))));
			return lowered;
		}
		// A set, or a map's keys or values, whose entries are cleared as the loop passes them, which Lua allows
		Expression table;
		List<String> names;
		String item;
		if (types().isSet(receiverType) && !(receiver instanceof MethodInvocationTree)) {
			table = body.expression(receiver);
			item = inlined != null ? inlined.parameters().getFirst() : unit().freshLocal("item", receiver);
			names = List.of(item);
		} else if (receiver instanceof MethodInvocationTree view && view.getArguments().isEmpty()
				&& view.getMethodSelect() instanceof MemberSelectTree select
				&& types().isMap(unit().type(select.getExpression()))
				&& (select.getIdentifier().contentEquals("keySet") || select.getIdentifier().contentEquals("values"))) {
			table = body.expression(select.getExpression());
			item = inlined != null ? inlined.parameters().getFirst() : unit().freshLocal("item", receiver);
			names = select.getIdentifier().contentEquals("keySet")
					? List.of(item)
					: List.of(unit().freshLocal("key", receiver), item);
		} else {
			return null;
		}
		Expression cleared = new Expression.Index(table, name(names.getFirst()));
		lowered.add(new Statement.GenericFor(names, List.of(call(name("pairs"), table)), Block.of(new Statement.If(
				List.of(new Statement.If.Branch(condition(inlined, action, item),
						Block.of(assign(cleared, new Expression.Nil())))), null))));
		return lowered;
	}

	/**
	 * {@code opt.ifPresent(x -> ...)} as {@code if opt ~= nil then local x = opt ... end}, and
	 * {@code ifPresentOrElse} with an else branch. A value that is not a name goes into a local first, so that it is
	 * read once: the lambda's own {@code local x = find()} when that hides no other local.
	 */
	private List<Statement> ifPresent(ExpressionTree receiver, List<? extends ExpressionTree> arguments) {
		List<Statement> lowered = new ArrayList<>();
		Expression value = body.expression(receiver);
		BodyLowering.InlinedLambda present = body.inline(arguments.getFirst());
		@Nullable VariableTree parameter = present == null
				? null
				: ((LambdaExpressionTree) BodyLowering.withoutParentheses(arguments.getFirst())).getParameters().getFirst();
		if (present != null && parameter != null && !(value instanceof Expression.Name) && unit().canHoist(parameter)) {
			lowered.add(new Statement.Local(List.of(present.parameters().getFirst()), List.of(value)));
			value = name(present.parameters().getFirst());
		} else if (!(value instanceof Expression.Name)) {
			String local = body.invent("value", receiver);
			lowered.add(new Statement.Local(List.of(local), List.of(value)));
			value = name(local);
		}
		Block then;
		if (present == null) {
			Expression action = callable(arguments.getFirst(), lowered);
			then = Block.of(new Statement.CallStatement(new Expression.Call(action, List.of(value))));
		} else if (value instanceof Expression.Name valueName && valueName.name().equals(present.parameters().getFirst())) {
			then = present.body();
		} else {
			then = prepended(new Statement.Local(List.of(present.parameters().getFirst()), List.of(value)), present.body());
		}
		Block otherwise = null;
		if (arguments.size() == 2) {
			BodyLowering.InlinedLambda absent = body.inline(arguments.get(1));
			otherwise = absent != null
					? absent.body()
					: Block.of(new Statement.CallStatement(
							new Expression.Call(callable(arguments.get(1), lowered), List.of())));
		}
		lowered.add(new Statement.If(List.of(new Statement.If.Branch(
				binary(value, BinaryOperator.NOT_EQUAL, new Expression.Nil()), then)), otherwise));
		return lowered;
	}

	static Block prepended(Statement first, Block block) {
		List<Block.Entry> entries = new ArrayList<>();
		entries.add(Block.Entry.of(first));
		entries.addAll(block.entries());
		return new Block(entries, block.closingComments());
	}

	/** A predicate's test of one element: the lambda's own expression, or a call of the function on the element. */
	private Expression condition(BodyLowering.@Nullable InlinedLambda inlined, @Nullable Expression action, String item) {
		if (inlined != null && inlined.value() != null) {
			return body.expression(inlined.value());
		}
		return new Expression.Call(Objects.requireNonNull(action), List.of(name(item)));
	}

	/** A function to call in a loop: a name as it is; anything else into a local before the loop, called by name. */
	private Expression callable(ExpressionTree function, List<Statement> before) {
		return callable(body.expression(function), function, before);
	}

	private Expression callable(Expression lowered, ExpressionTree function, List<Statement> before) {
		if (lowered instanceof Expression.Name) {
			return lowered;
		}
		String local = body.invent("action", function);
		before.add(new Statement.Local(List.of(local), List.of(lowered)));
		return name(local);
	}

	/**
	 * {@code counts.merge(key, 1, Integer::sum)} as {@code (counts[key] or 0) + 1}, and {@code String::concat} as
	 * {@code ..}: what a merge that adds or joins reads as in Lua. Null for any other merge.
	 */
	private @Nullable Expression summed(ExpressionTree receiver, List<? extends ExpressionTree> arguments) {
		if (!(arguments.get(2) instanceof MemberReferenceTree reference)
				|| !(unit().element(reference) instanceof ExecutableElement method)) {
			return null;
		}
		String owner = JavaTypes.owner(method).getQualifiedName().toString();
		String name = method.getSimpleName().toString();
		Expression stored = new Expression.Index(stableReceiver(receiver), stableReceiver(arguments.getFirst()));
		if (name.equals("sum") && isNumberBox(JavaTypes.owner(method))) {
			Expression zero = owner.equals("java.lang.Double") ? new Expression.NumberLiteral("0.0") : integer(0);
			return binary(new Expression.Parenthesized(binary(stored, BinaryOperator.OR, zero)), BinaryOperator.ADD,
					body.expression(arguments.get(1)));
		}
		if (name.equals("concat") && owner.equals("java.lang.String")) {
			return binary(new Expression.Parenthesized(binary(stored, BinaryOperator.OR, Expression.string(""))),
					BinaryOperator.CONCAT, body.expression(arguments.get(1)));
		}
		return null;
	}

	/**
	 * A value, or a default where it is nil: {@code value or d}. A value that can be false, which {@code or} would
	 * replace, is tested against a literal default instead, or goes through the helper {@code valueOr}.
	 */
	private Expression orDefault(Expression value, boolean canBeFalse, ExpressionTree fallback) {
		if (!canBeFalse) {
			return binary(value, BinaryOperator.OR, body.expression(fallback));
		}
		if (fallback instanceof LiteralTree literal && literal.getValue() instanceof Boolean truth) {
			return truth
					? binary(value, BinaryOperator.NOT_EQUAL, new Expression.BooleanLiteral(false))
					: binary(value, BinaryOperator.EQUAL, new Expression.BooleanLiteral(true));
		}
		unit().use(Helper.VALUE_OR);
		return Helper.VALUE_OR.invoke(value, body.expression(fallback));
	}

	/** A call of one of the map-default helpers: the map, the key, then the value or function Java passes. */
	private Expression mapDefault(Helper helper, ExpressionTree receiver, List<? extends ExpressionTree> arguments) {
		unit().use(helper);
		List<Expression> passed = new ArrayList<>();
		passed.add(body.expression(receiver));
		passed.add(body.expression(arguments.getFirst()));
		for (ExpressionTree argument : arguments.subList(1, arguments.size())) {
			passed.add(element(argument));
		}
		return helper.invoke(passed.toArray(Expression[]::new));
	}

	/**
	 * The edits of a {@code StringBuilder}, a list of pieces, that need its whole text: each makes the text and puts the
	 * changed text back with {@code builderSet}. Null for any other method.
	 */
	private @Nullable List<Statement> builderEdit(String method, ExpressionTree receiver,
			List<? extends ExpressionTree> arguments) {
		if (!List.of("setLength", "reverse", "insert", "deleteCharAt", "delete", "setCharAt").contains(method)) {
			return null;
		}
		Expression builder = stableReceiver(receiver);
		Expression whole = call(field(name("table"), "concat"), builder);
		unit().use(Helper.BUILDER_SET);
		if (method.equals("setLength")) {
			ExpressionTree length = arguments.getFirst();
			Expression kept = length instanceof LiteralTree literal && Integer.valueOf(0).equals(literal.getValue())
					? Expression.string("")
					: methodCall(whole, "sub", integer(1), body.expression(length));
			return List.of(new Statement.CallStatement(Helper.BUILDER_SET.invoke(builder, kept)));
		}
		if (method.equals("reverse")) {
			return List.of(new Statement.CallStatement(Helper.BUILDER_SET.invoke(builder, methodCall(whole, "reverse"))));
		}
		// The others cut the text at a position, so it goes into a local first
		String local = body.invent("text", receiver);
		Expression text = name(local);
		Expression position = body.expression(arguments.getFirst());
		Expression changed = switch (method) {
			case "insert" -> concatenation(methodCall(text, "sub", integer(1), position),
					body.strings().asText(arguments.get(1)), methodCall(text, "sub", BodyLowering.plusOne(position)));
			case "deleteCharAt" -> concatenation(methodCall(text, "sub", integer(1), position),
					methodCall(text, "sub", binary(position, BinaryOperator.ADD, integer(2))));
			case "delete" -> concatenation(methodCall(text, "sub", integer(1), position),
					methodCall(text, "sub", BodyLowering.plusOne(body.expression(arguments.get(1)))));
			default -> concatenation(methodCall(text, "sub", integer(1), position), body.expression(arguments.get(1)),
					methodCall(text, "sub", binary(position, BinaryOperator.ADD, integer(2))));
		};
		return List.of(new Statement.Local(List.of(local), List.of(whole)),
				new Statement.CallStatement(Helper.BUILDER_SET.invoke(builder, changed)));
	}

	/** The parts joined with {@code ..}, nested to the right as Lua groups it, so that none needs parentheses. */
	private static Expression concatenation(Expression... parts) {
		Expression joined = parts[parts.length - 1];
		for (int index = parts.length - 2; index >= 0; index--) {
			joined = binary(parts[index], BinaryOperator.CONCAT, joined);
		}
		return joined;
	}

	/**
	 * The separator a {@code split} regular expression stands for when it matches only itself: {@code ","} or
	 * {@code "\\|"}; null for a real pattern, such as {@code "\\s+"}, or an empty one, which splits every character.
	 */
	private static @Nullable String plainSeparator(ExpressionTree regex) {
		if (!(regex instanceof LiteralTree literal) || !(literal.getValue() instanceof String pattern) || pattern.isEmpty()) {
			return null;
		}
		StringBuilder plain = new StringBuilder();
		for (int index = 0; index < pattern.length(); index++) {
			char c = pattern.charAt(index);
			if (c == '\\') {
				if (index + 1 >= pattern.length() || Character.isLetterOrDigit(pattern.charAt(index + 1))) {
					return null;
				}
				plain.append(pattern.charAt(++index));
			} else if (".$|()[]{}^?*+".indexOf(c) >= 0) {
				return null;
			} else {
				plain.append(c);
			}
		}
		return plain.toString();
	}

	Expression invocation(MethodInvocationTree call, ExecutableElement method, @Nullable ExpressionTree receiver) {
		String name = method.getSimpleName().toString();
		List<? extends ExpressionTree> arguments = call.getArguments();
		boolean isStatic = method.getModifiers().contains(Modifier.STATIC);
		String owner = JavaTypes.owner(method).getQualifiedName().toString();
		if (types().isStream(unit().type(call)) && !name.equals("chars") && !name.equals("codePoints")) {
			throw new Rejection(call, STREAM_IN_STATEMENT);
		}
		if (!isStatic && receiver != null) {
			TypeMirror receiverType = unit().type(receiver);
			if (types().isStream(receiverType)) {
				throw new Rejection(call, STREAM_IN_STATEMENT);
			}
			if (types().isApiType(receiverType) && ((DeclaredType) receiverType).asElement() instanceof TypeElement api
					&& api.getKind() == ElementKind.ENUM) {
				return apiEnumMethod(call, name, receiver, api, arguments);
			}
			if (types().isPluginEnum(receiverType)) {
				return enumMethod(call, name, body.expression(receiver), types().isObjectEnum(receiverType), arguments);
			}
			if (types().isString(receiverType)) {
				return stringMethod(call, name, body.expression(receiver), arguments);
			}
			boolean collection = types().isList(receiverType) || types().isMap(receiverType)
					|| types().isSet(receiverType) || types().isDeque(receiverType);
			if (collection && name.equals("toString") && arguments.isEmpty()) {
				return body.strings().toText(receiver);
			}
			if (collection && (name.equals("iterator") || name.equals("listIterator") || name.equals("spliterator"))) {
				throw new Rejection(call, NO_ITERATOR);
			}
			if (types().isList(receiverType)) {
				return listMethod(call, name, receiver, arguments);
			}
			if (types().isMap(receiverType)) {
				return mapMethod(call, name, receiver, arguments);
			}
			if (types().isSet(receiverType)) {
				return setMethod(call, name, receiver, arguments);
			}
			if (types().isDeque(receiverType)) {
				return dequeMethod(call, name, body.expression(receiver), arguments);
			}
			if (types().isOptional(receiverType)) {
				return optionalMethod(call, name, body.expression(receiver), receiverType, arguments);
			}
			if (types().isStringBuilder(receiverType)) {
				if (name.equals("toString")) {
					return call(field(name("table"), "concat"), body.expression(receiver));
				}
				Expression whole = call(field(name("table"), "concat"), body.expression(receiver));
				return switch (name) {
					case "length" -> length(whole);
					case "isEmpty" -> binary(whole, BinaryOperator.EQUAL, Expression.string(""));
					case "charAt" -> characterAt(whole, arguments.getFirst());
					case "indexOf" -> {
						unit().use(Helper.STRING_INDEX_OF);
						yield Helper.STRING_INDEX_OF.invoke(whole, body.expression(arguments.getFirst()));
					}
					case "append", "setLength", "reverse", "insert", "deleteCharAt", "delete", "setCharAt" ->
							throw new Rejection(call, STATEMENT_ONLY.formatted(name));
					default -> throw Rejection.notYet(call, "StringBuilder." + name);
				};
			}
			if (isSystemOut(receiver)) {
				return print(call, name, arguments);
			}
			if (isBoxed(receiverType)) {
				return boxedMethod(call, name, body.expression(receiver), arguments);
			}
			if (name.equals("equals") && arguments.size() == 1) {
				// Object.equals, not overridden by the plugin class: identity, as Lua's == compares tables
				return binary(body.expression(receiver), BinaryOperator.EQUAL, body.expression(arguments.getFirst()));
			}
			if (name.equals("hashCode") || name.equals("getClass")) {
				throw new Rejection(call, "Lua has no runtime type information to match " + name + "().");
			}
			// A receiver that is itself unsupported, such as names.stream() in names.stream().count(), is the problem
			// to report
			body.expression(receiver);
			throw Rejection.notYet(call, "Calling " + name + " on " + simpleName(receiverType));
		}
		return switch (owner) {
			case "java.lang.Math" -> mathMethod(call, name, arguments);
			// name() or ordinal() in an enum's own code, which only an object enum has
			case "java.lang.Enum" -> enumMethod(call, name, body.self(call), true, arguments);
			case "java.lang.Character" -> characterStatic(call, name, arguments);
			case "java.util.Optional", "java.util.OptionalInt", "java.util.OptionalLong", "java.util.OptionalDouble" ->
					switch (name) {
						case "of", "ofNullable" -> body.expression(arguments.getFirst());
						case "empty" -> new Expression.Nil();
						default -> notYet(call, simpleName(unit().type(call)) + "." + name);
					};
			case "java.lang.String" -> stringStatic(call, name, arguments);
			case "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte", "java.lang.Double",
					"java.lang.Float", "java.lang.Boolean" -> boxedStatic(call, name, owner, arguments);
			case "java.util.List" -> name.equals("of")
					? positional(arguments)
					: notYet(call, "List." + name);
			case "java.util.Set" -> name.equals("of") ? setOf(arguments) : notYet(call, "Set." + name);
			case "java.util.Map" -> name.equals("of") ? mapOf(arguments) : notYet(call, "Map." + name);
			case "java.util.Collections" -> name.equals("sort")
					? sort(body.expression(arguments.getFirst()), arguments.size() > 1 ? arguments.get(1) : null)
					: notYet(call, "Collections." + name);
			default -> notYet(call, "Calling " + owner + "." + name);
		};
	}

	private static Expression notYet(Tree tree, String construct) {
		throw Rejection.notYet(tree, construct);
	}

	/** A type as a message names it: {@code Stream} rather than {@code java.util.stream.Stream<java.lang.String>}. */
	private static String simpleName(TypeMirror type) {
		return type instanceof DeclaredType declared ? declared.asElement().getSimpleName().toString() : type.toString();
	}

	/**
	 * A method of an enum value. A simple enum's value is the string of the constant's name, so {@code name()} is the
	 * value itself; an object enum's constant keeps its name and ordinal, {@code mode.name} and {@code mode.ordinal}.
	 */
	private Expression enumMethod(MethodInvocationTree call, String name, Expression value, boolean object,
			List<? extends ExpressionTree> arguments) {
		return switch (name) {
			case "name", "toString" -> object ? field(value, "name") : value;
			case "ordinal" -> field(value, "ordinal");
			// Java's compareTo gives the difference of the ordinals
			case "compareTo" -> binary(field(value, "ordinal"), BinaryOperator.SUBTRACT,
					field(body.expression(arguments.getFirst()), "ordinal"));
			case "equals" -> binary(value, BinaryOperator.EQUAL, body.expression(arguments.getFirst()));
			default -> throw Rejection.notYet(call, "Calling " + name + " on an enum value");
		};
	}

	/**
	 * A method of a client enum's value, which is the client's number: {@code name()} and {@code toString()} are
	 * {@code GameState.FromID(state)}, where the table has {@code FromID}.
	 */
	private Expression apiEnumMethod(MethodInvocationTree call, String name, ExpressionTree receiver, TypeElement api,
			List<? extends ExpressionTree> arguments) {
		return switch (name) {
			case "name", "toString" -> body.strings().apiEnumName(receiver, api, call);
			case "equals" -> binary(body.expression(receiver), BinaryOperator.EQUAL,
					body.expression(arguments.getFirst()));
			default -> throw new Rejection(call, "A client enum's value is the client's number, which has no "
					+ name + "(); compare it with the enum's constants instead.");
		};
	}

	// ---- String

	private Expression stringMethod(MethodInvocationTree call, String name, Expression text,
			List<? extends ExpressionTree> arguments) {
		return switch (name) {
			case "length" -> length(text);
			case "isEmpty" -> binary(text, BinaryOperator.EQUAL, Expression.string(""));
			case "equals" -> binary(text, BinaryOperator.EQUAL, body.expression(arguments.getFirst()));
			case "equalsIgnoreCase" -> binary(methodCall(text, "lower"), BinaryOperator.EQUAL,
					methodCall(body.expression(arguments.getFirst()), "lower"));
			case "substring" -> arguments.size() == 1
					? methodCall(text, "sub", BodyLowering.plusOne(body.expression(arguments.getFirst())))
					: methodCall(text, "sub", BodyLowering.plusOne(body.expression(arguments.getFirst())),
							body.expression(arguments.get(1)));
			case "contains" -> binary(
					methodCall(text, "find", body.expression(arguments.getFirst()), integer(1),
							new Expression.BooleanLiteral(true)),
					BinaryOperator.NOT_EQUAL, new Expression.Nil());
			case "indexOf" -> {
				ExpressionTree sought = arguments.getFirst();
				if (arguments.size() != 1) {
					throw Rejection.notYet(call, "String.indexOf with a start position");
				}
				unit().use(Helper.STRING_INDEX_OF);
				Expression soughtText;
				if (types().isString(unit().type(sought))) {
					soughtText = body.expression(sought);
				} else if (isCharType(sought)) {
					// indexOf(int) takes a char as its number, but a char is the one-character string to find
					soughtText = body.asWritten(sought);
				} else {
					soughtText = characterOf(sought);
				}
				yield Helper.STRING_INDEX_OF.invoke(text, soughtText);
			}
			case "startsWith" -> {
				Expression prefix = body.expression(arguments.getFirst());
				yield binary(methodCall(text, "sub", integer(1), byteLength(arguments.getFirst(), prefix)),
						BinaryOperator.EQUAL, prefix);
			}
			case "endsWith" -> {
				Expression suffix = body.expression(arguments.getFirst());
				if (arguments.getFirst() instanceof LiteralTree literal && literal.getValue() instanceof String value
						&& !value.isEmpty()) {
					yield binary(methodCall(text, "sub", new Expression.Unary(UnaryOperator.NEGATE,
									integer(value.getBytes(StandardCharsets.UTF_8).length))),
							BinaryOperator.EQUAL, suffix);
				}
				unit().use(Helper.ENDS_WITH);
				yield Helper.ENDS_WITH.invoke(text, suffix);
			}
			case "toUpperCase" -> noLocale(call, methodCall(text, "upper"));
			case "toLowerCase" -> noLocale(call, methodCall(text, "lower"));
			case "trim", "strip" -> {
				unit().use(Helper.TRIM);
				yield Helper.TRIM.invoke(text);
			}
			case "repeat" -> methodCall(text, "rep", body.expression(arguments.getFirst()));
			case "toString" -> text;
			case "split" -> {
				String separator = plainSeparator(arguments.getFirst());
				if (separator == null) {
					throw new Rejection(call, "Lua has no regular expressions, so split takes only a separator that"
							+ " stands for itself, such as a comma or an escaped bar; for a pattern, use LuaString.gmatch.");
				}
				unit().use(Helper.SPLIT);
				yield arguments.size() == 1
						? Helper.SPLIT.invoke(text, Expression.string(separator))
						: Helper.SPLIT.invoke(text, Expression.string(separator), body.expression(arguments.get(1)));
			}
			case "matches", "replaceAll", "replaceFirst" -> throw new Rejection(call, "Lua has no regular"
					+ " expressions; use LuaString.match, find, gsub or gmatch with a Lua pattern.");
			case "charAt" -> characterAt(text, arguments.getFirst());
			case "toCharArray" -> {
				unit().use(Helper.CHARACTERS);
				yield Helper.CHARACTERS.invoke(text);
			}
			case "chars", "codePoints" -> throw new Rejection(call, NO_CHAR_STREAM);
			case "codePointAt" -> throw new Rejection(call, "A Lua string holds bytes, not code points; (int)"
					+ " text.charAt(i) is the byte's number, which is the code point for ASCII text.");
			default -> throw Rejection.notYet(call, "String." + name);
		};
	}

	/**
	 * The character at a Java index, as a one-character string: {@code text:sub(i + 1, i + 1)}. The index appears
	 * twice, so its Lua may not call anything; {@code s.length() - 1} is {@code #s}, which is no call.
	 */
	private Expression characterAt(Expression text, ExpressionTree position) {
		Expression index = body.index(position);
		if (callsAnything(index)) {
			throw new Rejection(position, "Lua would evaluate this index twice; store it in a local first.");
		}
		return methodCall(text, "sub", index, index);
	}

	/** Whether Lua calls a function to evaluate the expression, so that evaluating it twice could differ. */
	private static boolean callsAnything(Expression expression) {
		return switch (expression) {
			case Expression.Call _, Expression.MethodCall _ -> true;
			case Expression.Index index -> callsAnything(index.target()) || callsAnything(index.key());
			case Expression.Field field -> callsAnything(field.target());
			case Expression.Binary binary -> callsAnything(binary.left()) || callsAnything(binary.right());
			case Expression.Unary unary -> callsAnything(unary.operand());
			case Expression.Parenthesized parenthesized -> callsAnything(parenthesized.inner());
			default -> false;
		};
	}

	/** A value Java takes as a char: a char as the string it is, a number as the character it stands for. */
	private Expression characterOf(ExpressionTree value) {
		if (isCharType(value)) {
			return body.expression(value);
		}
		return call(field(name("string"), "char"), body.expression(value));
	}

	private boolean isCharType(ExpressionTree value) {
		return types().isChar(unit().type(value));
	}

	private boolean isCharArray(ExpressionTree value) {
		return unit().type(value) instanceof ArrayType array && types().isChar(array.getComponentType());
	}

	private static Expression noLocale(MethodInvocationTree call, Expression lowered) {
		if (!call.getArguments().isEmpty()) {
			throw Rejection.notYet(call, "Changing case with a Locale");
		}
		return lowered;
	}

	/** {@code #text}, or the byte count itself when the text is a literal. */
	private static Expression byteLength(ExpressionTree tree, Expression lowered) {
		if (tree instanceof LiteralTree literal && literal.getValue() instanceof String value) {
			return integer(value.getBytes(StandardCharsets.UTF_8).length);
		}
		return length(lowered);
	}

	private Expression stringStatic(MethodInvocationTree call, String name, List<? extends ExpressionTree> arguments) {
		return switch (name) {
			case "valueOf", "copyValueOf" -> isCharArray(arguments.getFirst())
					? call(field(name("table"), "concat"), body.expression(arguments.getFirst()))
					: body.strings().toText(arguments.getFirst());
			case "format" -> body.strings().format(call);
			case "join" -> {
				ExpressionTree elements = arguments.get(1);
				Expression list = arguments.size() == 2 && (types().isList(unit().type(elements))
						|| unit().type(elements).getKind() == TypeKind.ARRAY)
						? body.expression(elements)
						: positional(arguments.subList(1, arguments.size()));
				yield call(field(name("table"), "concat"), list, body.expression(arguments.getFirst()));
			}
			default -> throw Rejection.notYet(call, "String." + name);
		};
	}

	// ---- Optional

	/**
	 * A method of an {@code Optional}, which in Lua is its value or nil: {@code isPresent()} is {@code opt ~= nil},
	 * {@code get()} the value itself, {@code orElse(d)} {@code opt or d}. An empty one gives nil where Java's
	 * {@code get()} throws.
	 */
	Expression optionalMethod(MethodInvocationTree call, String name, Expression value, TypeMirror type,
			List<? extends ExpressionTree> arguments) {
		boolean canBeFalse = types().is(type, "java.util.Optional") && types().isBoolean(types().typeArgument(type, 0));
		return switch (name) {
			case "isPresent" -> binary(value, BinaryOperator.NOT_EQUAL, new Expression.Nil());
			case "isEmpty" -> binary(value, BinaryOperator.EQUAL, new Expression.Nil());
			case "get", "getAsInt", "getAsLong", "getAsDouble" -> value;
			case "orElse" -> arguments.getFirst() instanceof LiteralTree literal && literal.getValue() == null
					? value
					: orDefault(value, canBeFalse, arguments.getFirst());
			case "orElseGet" -> {
				if (canBeFalse) {
					throw Rejection.notYet(call, "orElseGet on an Optional of Boolean");
				}
				yield binary(value, BinaryOperator.OR, supplied(arguments.getFirst()));
			}
			case "orElseThrow" -> {
				if (arguments.isEmpty()) {
					yield value;
				}
				if (canBeFalse) {
					throw Rejection.notYet(call, "orElseThrow on an Optional of Boolean");
				}
				if (!(BodyLowering.withoutParentheses(arguments.getFirst()) instanceof LambdaExpressionTree lambda)
						|| !(lambda.getBody() instanceof ExpressionTree made)
						|| !(BodyLowering.withoutParentheses(made) instanceof NewClassTree creation)) {
					throw Rejection.notYet(call, "orElseThrow with anything but a lambda that makes the exception");
				}
				yield binary(value, BinaryOperator.OR, body.errorCall(creation));
			}
			case "equals" -> binary(value, BinaryOperator.EQUAL, body.expression(arguments.getFirst()));
			case "map", "flatMap", "filter", "or", "stream", "ifPresent", "ifPresentOrElse" ->
					throw new Rejection(call, "In Lua an Optional is its value or nil, with no box around it to "
							+ name + "; test it with isPresent() and work on the value.");
			default -> throw Rejection.notYet(call, "Optional." + name);
		};
	}

	/** What a supplier gives: a lambda's expression, which {@code or} evaluates only when needed, or a call. */
	private Expression supplied(ExpressionTree supplier) {
		if (BodyLowering.withoutParentheses(supplier) instanceof LambdaExpressionTree lambda
				&& lambda.getBody() instanceof ExpressionTree expression) {
			return body.expression(expression);
		}
		return new Expression.Call(body.expression(supplier), List.of());
	}

	// ---- Boxed numbers and Math

	private boolean isBoxed(TypeMirror type) {
		return types().is(type, "java.lang.Integer") || types().is(type, "java.lang.Long")
				|| types().is(type, "java.lang.Double") || types().is(type, "java.lang.Float")
				|| types().is(type, "java.lang.Short") || types().is(type, "java.lang.Byte")
				|| types().is(type, "java.lang.Boolean") || types().is(type, "java.lang.Character");
	}

	private Expression boxedMethod(MethodInvocationTree call, String name, Expression value,
			List<? extends ExpressionTree> arguments) {
		return switch (name) {
			case "intValue", "longValue", "doubleValue", "floatValue", "shortValue", "byteValue", "booleanValue",
					"charValue" -> value;
			case "equals" -> binary(value, BinaryOperator.EQUAL, body.expression(arguments.getFirst()));
			default -> throw Rejection.notYet(call, "Calling " + name + " on a boxed number");
		};
	}

	private Expression boxedStatic(MethodInvocationTree call, String name, String owner,
			List<? extends ExpressionTree> arguments) {
		boolean floating = owner.equals("java.lang.Double") || owner.equals("java.lang.Float");
		return switch (name) {
			case "parseInt", "parseLong", "parseShort", "parseByte" ->
					call(name("tonumber"), body.expression(arguments.getFirst()), integer(10));
			case "parseDouble", "parseFloat" -> call(name("tonumber"), body.expression(arguments.getFirst()));
			case "toString" -> body.strings().toText(arguments.getFirst());
			case "valueOf" -> {
				ExpressionTree argument = arguments.getFirst();
				if (types().isString(unit().type(argument))) {
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
	private Expression characterStatic(MethodInvocationTree call, String name, List<? extends ExpressionTree> arguments) {
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
		if (characterClass != null) {
			return binary(methodCall(characterOf(argument), "find", Expression.string(characterClass)),
					BinaryOperator.NOT_EQUAL, new Expression.Nil());
		}
		if ((name.equals("toUpperCase") || name.equals("toLowerCase")) && !isCharType(argument)) {
			throw Rejection.notYet(call, "Character." + name + " of a number");
		}
		return switch (name) {
			case "toUpperCase" -> methodCall(characterOf(argument), "upper");
			case "toLowerCase" -> methodCall(characterOf(argument), "lower");
			case "toString", "valueOf" -> characterOf(argument);
			// tonumber reads the letters after 9 as digits, a as 10, as Java does
			case "getNumericValue" -> binary(call(name("tonumber"), characterOf(argument), integer(36)),
					BinaryOperator.OR, integer(-1));
			case "digit" -> binary(call(name("tonumber"), characterOf(argument), body.expression(arguments.get(1))),
					BinaryOperator.OR, integer(-1));
			default -> throw Rejection.notYet(call, "Character." + name);
		};
	}

	private Expression mathMethod(MethodInvocationTree call, String name, List<? extends ExpressionTree> arguments) {
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

	// ---- Lists, maps and sets

	private Expression listMethod(MethodInvocationTree call, String name, ExpressionTree receiver,
			List<? extends ExpressionTree> arguments) {
		Expression list = body.expression(receiver);
		return switch (name) {
			case "get" -> body.listElement(list, arguments.getFirst());
			case "getFirst" -> new Expression.Index(list, integer(1));
			case "getLast" -> new Expression.Index(list, length(list));
			case "removeFirst" -> call(field(name("table"), "remove"), list, integer(1));
			case "removeLast" -> call(field(name("table"), "remove"), list);
			case "size" -> length(list);
			case "isEmpty" -> binary(length(list), BinaryOperator.EQUAL, integer(0));
			case "contains" -> {
				unit().use(Helper.LIST_CONTAINS);
				yield Helper.LIST_CONTAINS.invoke(list, body.expression(arguments.getFirst()));
			}
			case "indexOf" -> {
				unit().use(Helper.LIST_INDEX_OF);
				yield Helper.LIST_INDEX_OF.invoke(list, body.expression(arguments.getFirst()));
			}
			case "remove" -> {
				if (unit().type(arguments.getFirst()).getKind() != TypeKind.INT) {
					throw Rejection.notYet(call, "List.remove of an element rather than an index");
				}
				yield call(field(name("table"), "remove"), list, BodyLowering.plusOne(body.expression(arguments.getFirst())));
			}
			case "add" -> {
				if (arguments.size() != 2) {
					throw new Rejection(call, STATEMENT_ONLY.formatted("add"));
				}
				yield call(field(name("table"), "insert"), list,
						BodyLowering.plusOne(body.expression(arguments.getFirst())), element(arguments.get(1)));
			}
			case "set", "addAll", "forEach", "removeIf" -> throw new Rejection(call, STATEMENT_ONLY.formatted(name));
			case "sort" -> sort(list, arguments.getFirst());
			case "stream" -> throw new Rejection(call, STREAM_IN_STATEMENT);
			default -> {
				if (types().isDeque(unit().type(receiver))) {
					yield dequeMethod(call, name, list, arguments);
				}
				throw Rejection.notYet(call, "List." + name);
			}
		};
	}

	private Expression mapMethod(MethodInvocationTree call, String name, ExpressionTree receiver,
			List<? extends ExpressionTree> arguments) {
		Expression map = body.expression(receiver);
		return switch (name) {
			case "get" -> {
				checkKey(arguments.getFirst());
				yield new Expression.Index(map, body.expression(arguments.getFirst()));
			}
			case "getOrDefault" -> {
				checkKey(arguments.getFirst());
				Expression stored = new Expression.Index(map, body.expression(arguments.getFirst()));
				yield orDefault(stored, types().isBoolean(types().typeArgument(unit().type(receiver), 1)),
						arguments.get(1));
			}
			case "computeIfAbsent" -> {
				checkKey(arguments.getFirst());
				yield mapDefault(Helper.COMPUTE_IF_ABSENT, receiver, arguments);
			}
			case "compute" -> {
				checkKey(arguments.getFirst());
				yield mapDefault(Helper.COMPUTE, receiver, arguments);
			}
			case "computeIfPresent" -> {
				checkKey(arguments.getFirst());
				yield mapDefault(Helper.COMPUTE_IF_PRESENT, receiver, arguments);
			}
			case "merge" -> {
				checkKey(arguments.getFirst());
				yield mapDefault(Helper.MERGE, receiver, arguments);
			}
			case "containsKey" -> {
				checkKey(arguments.getFirst());
				yield binary(new Expression.Index(map, body.expression(arguments.getFirst())), BinaryOperator.NOT_EQUAL,
						new Expression.Nil());
			}
			case "size" -> {
				unit().use(Helper.COUNT);
				yield Helper.COUNT.invoke(map);
			}
			case "isEmpty" -> binary(call(name("next"), map), BinaryOperator.EQUAL, new Expression.Nil());
			case "put", "remove", "putIfAbsent", "forEach" -> throw new Rejection(call, STATEMENT_ONLY.formatted(name));
			case "keySet", "values", "entrySet" -> throw Rejection.notYet(call, "Map." + name + " outside a for-each loop");
			case "firstKey" -> {
				unit().use(Helper.FIRST_KEY);
				yield Helper.FIRST_KEY.invoke(map);
			}
			case "lastKey" -> {
				unit().use(Helper.LAST_KEY);
				yield Helper.LAST_KEY.invoke(map);
			}
			default -> throw Rejection.notYet(call, "Map." + name);
		};
	}

	private Expression setMethod(MethodInvocationTree call, String name, ExpressionTree receiver,
			List<? extends ExpressionTree> arguments) {
		Expression set = body.expression(receiver);
		return switch (name) {
			case "contains" -> {
				checkKey(arguments.getFirst());
				yield binary(new Expression.Index(set, body.expression(arguments.getFirst())),
						BinaryOperator.NOT_EQUAL, new Expression.Nil());
			}
			case "size" -> {
				unit().use(Helper.COUNT);
				yield Helper.COUNT.invoke(set);
			}
			case "isEmpty" -> binary(call(name("next"), set), BinaryOperator.EQUAL, new Expression.Nil());
			case "stream" -> throw new Rejection(call, STREAM_IN_STATEMENT);
			case "add", "remove", "forEach", "removeIf", "addAll" ->
					throw new Rejection(call, STATEMENT_ONLY.formatted(name));
			case "first", "getFirst" -> {
				unit().use(Helper.FIRST_KEY);
				yield Helper.FIRST_KEY.invoke(set);
			}
			case "last", "getLast" -> {
				unit().use(Helper.LAST_KEY);
				yield Helper.LAST_KEY.invoke(set);
			}
			default -> throw Rejection.notYet(call, "Set." + name);
		};
	}

	/**
	 * A queue's or deque's method on its Lua list, whose first element is the front: {@code poll()} removes it,
	 * {@code peek()} reads it. An empty queue gives nil, where Java's {@code remove()} and {@code element()} would throw.
	 */
	private Expression dequeMethod(MethodInvocationTree call, String name, Expression queue,
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
			case "peekLast", "getLast" -> new Expression.Index(queue, length(queue));
			case "size" -> length(queue);
			case "isEmpty" -> binary(length(queue), BinaryOperator.EQUAL, integer(0));
			case "contains" -> {
				unit().use(Helper.LIST_CONTAINS);
				yield Helper.LIST_CONTAINS.invoke(queue, body.expression(arguments.getFirst()));
			}
			case "add", "addFirst", "addLast", "offer", "offerFirst", "offerLast", "push", "forEach", "removeIf" ->
					throw new Rejection(call, STATEMENT_ONLY.formatted(name));
			default -> throw Rejection.notYet(call, "Queue." + name);
		};
	}

	/**
	 * {@code table.sort} with Java's comparator turned into Lua's "comes before" function; see {@link Comparators}.
	 * Lua's sort is not stable, unlike Java's.
	 */
	private Expression sort(Expression list, @Nullable ExpressionTree comparator) {
		if (comparator == null || (comparator instanceof LiteralTree literal && literal.getValue() == null)) {
			return call(field(name("table"), "sort"), list);
		}
		return call(field(name("table"), "sort"), list, body.comparators().function(comparator));
	}

	static boolean isNumberBox(TypeElement type) {
		String name = type.getQualifiedName().toString();
		return name.equals("java.lang.Integer") || name.equals("java.lang.Long") || name.equals("java.lang.Double");
	}

	private Expression mapOf(List<? extends ExpressionTree> arguments) {
		List<Expression.TableField> fields = new ArrayList<>();
		for (int index = 0; index + 1 < arguments.size(); index += 2) {
			fields.add(entry(arguments.get(index), element(arguments.get(index + 1))));
		}
		return new Expression.Table(fields);
	}

	private Expression setOf(List<? extends ExpressionTree> elements) {
		return new Expression.Table(elements.stream()
				.map(element -> entry(element, new Expression.BooleanLiteral(true)))
				.toList());
	}

	/** {@code name = value} for a string key that is a Lua name, otherwise {@code [key] = value}. */
	private Expression.TableField entry(ExpressionTree key, Expression value) {
		checkKey(key);
		if (key instanceof LiteralTree literal && literal.getValue() instanceof String text && LuaNames.isName(text)) {
			return new Expression.TableField.Named(text, value);
		}
		return new Expression.TableField.Keyed(body.expression(key), value);
	}

	private Expression positional(List<? extends ExpressionTree> elements) {
		return new Expression.Table(elements.stream()
				.map(element -> (Expression.TableField) new Expression.TableField.Positional(element(element)))
				.toList());
	}

	/** {@code new ArrayList<>()}, {@code new HashMap<>()}, {@code new HashSet<>()} and {@code new StringBuilder()}. */
	Expression construct(NewClassTree creation, TypeElement type) {
		String name = type.getQualifiedName().toString();
		List<? extends ExpressionTree> arguments = creation.getArguments();
		// new StringBuilder('a') takes the char as its number, a capacity, as Java does
		boolean capacityOnly = arguments.isEmpty() || (arguments.size() == 1
				&& (unit().type(arguments.getFirst()).getKind() == TypeKind.INT || isCharType(arguments.getFirst())));
		return switch (name) {
			case "java.util.ArrayList", "java.util.LinkedList", "java.util.ArrayDeque" -> capacityOnly
					? new Expression.Table(List.of())
					: listCopy(creation, arguments.getFirst());
			case "java.util.HashMap", "java.util.HashSet" -> capacityOnly
					? new Expression.Table(List.of())
					: filled(creation, new Expression.Table(List.of()), name.endsWith("Set"), arguments.getFirst());
			case "java.lang.StringBuilder" -> {
				if (capacityOnly) {
					yield new Expression.Table(List.of());
				}
				yield new Expression.Table(List.of(
						new Expression.TableField.Positional(body.strings().asText(arguments.getFirst()))));
			}
			case "java.lang.String" -> {
				if (arguments.isEmpty()) {
					yield Expression.string("");
				}
				if (arguments.size() == 1 && isCharArray(arguments.getFirst())) {
					yield call(field(name("table"), "concat"), body.expression(arguments.getFirst()));
				}
				if (arguments.size() == 1 && types().isString(unit().type(arguments.getFirst()))) {
					yield body.expression(arguments.getFirst());
				}
				throw Rejection.notYet(creation, "This String constructor");
			}
			case "java.util.LinkedHashMap", "java.util.LinkedHashSet" -> {
				if (arguments.size() == 3) {
					throw new Rejection(creation, "An access-ordered LinkedHashMap moves a key on every get, which Lua"
							+ " cannot see a table read do; keep the order yourself, in a List of keys.");
				}
				unit().use(Helper.ORDERED_TABLE);
				Expression empty = Helper.ORDERED_TABLE.invoke();
				yield capacityOnly || arguments.size() == 2
						? empty
						: filled(creation, empty, name.endsWith("Set"), arguments.getFirst());
			}
			case "java.util.TreeMap", "java.util.TreeSet" -> {
				unit().use(Helper.SORTED_TABLE);
				if (arguments.isEmpty()) {
					yield Helper.SORTED_TABLE.invoke();
				}
				ExpressionTree argument = arguments.getFirst();
				if (types().isSubtype(unit().type(argument), "java.util.Comparator")) {
					yield Helper.SORTED_TABLE.invoke(body.comparators().function(argument));
				}
				if (types().isSubtype(unit().type(argument), "java.util.SortedMap")
						|| types().isSubtype(unit().type(argument), "java.util.SortedSet")) {
					throw new Rejection(argument, "Java copies a sorted map or set with its comparator, which a Lua table"
							+ " does not keep; make the new one with that comparator, then add the entries.");
				}
				yield filled(creation, Helper.SORTED_TABLE.invoke(), name.endsWith("Set"), argument);
			}
			default -> {
				if (types().isThrowable(type.asType())) {
					// An exception kept for later is its message, the value error raises
					yield body.errorMessage(creation);
				}
				throw Rejection.notYet(creation, "Creating " + type.getSimpleName());
			}
		};
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
		TypeMirror type = unit().type(source);
		if (types().isList(type) || types().isDeque(type) || type.getKind() == TypeKind.ARRAY) {
			return new Holding(Contents.ELEMENTS, source);
		}
		if (source instanceof MethodInvocationTree view && view.getArguments().isEmpty()
				&& view.getMethodSelect() instanceof MemberSelectTree select
				&& types().isMap(unit().type(select.getExpression()))) {
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
		if (types().isSet(type)) {
			return new Holding(Contents.KEYS, source);
		}
		if (types().isMap(type)) {
			return new Holding(Contents.ENTRIES, source);
		}
		throw Rejection.notYet(source, "Copying a " + simpleName(type));
	}

	/** {@code new ArrayList<>(other)}: a list's elements moved into a new table, or a set's keys or map's values. */
	private Expression listCopy(NewClassTree creation, ExpressionTree source) {
		Holding holding = holding(source);
		return switch (holding.contents()) {
			case ELEMENTS -> {
				Expression list = stableReceiver(source);
				yield call(field(name("table"), "move"), list, integer(1), length(list), integer(1),
						new Expression.Table(List.of()));
			}
			case KEYS -> {
				unit().use(Helper.KEYS_OF);
				yield Helper.KEYS_OF.invoke(body.expression(holding.table()));
			}
			case VALUES -> {
				unit().use(Helper.VALUES_OF);
				yield Helper.VALUES_OF.invoke(body.expression(holding.table()));
			}
			case ENTRIES -> throw Rejection.notYet(creation, "A list of a map's entries");
		};
	}

	/** A new set or map filled from another collection, through the helper that fits what it holds. */
	private Expression filled(NewClassTree creation, Expression empty, boolean set, ExpressionTree source) {
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
			throw Rejection.notYet(creation, "Filling a " + (set ? "set" : "map") + " from a " + simpleName(
					unit().type(source)));
		}
		unit().use(helper);
		return helper.invoke(empty, table);
	}

	/**
	 * {@code list.addAll(set)}, {@code set.addAll(list)} or {@code map.putAll(other)} as a loop over what the other
	 * collection holds; a list into a list is {@code table.move}.
	 */
	private List<Statement> addedAll(ExpressionTree receiver, String target, ExpressionTree source) {
		Holding holding = holding(source);
		Expression into = stableReceiver(receiver);
		if (target.equals("list") && holding.contents() == Contents.ELEMENTS) {
			Expression list = stableReceiver(source);
			return List.of(new Statement.CallStatement(call(field(name("table"), "move"),
					list, integer(1), length(list), nextIndex(into), into)));
		}
		Expression table = body.expression(holding.table());
		String key = unit().freshLocal("key", source);
		String value = unit().freshLocal("value", source);
		Statement stored = switch (target + " " + holding.contents()) {
			case "list KEYS" -> assign(new Expression.Index(into, nextIndex(into)), name(key));
			case "list VALUES" -> assign(new Expression.Index(into, nextIndex(into)), name(value));
			case "set ELEMENTS" -> assign(new Expression.Index(into, name(value)), new Expression.BooleanLiteral(true));
			case "set KEYS" -> assign(new Expression.Index(into, name(key)), new Expression.BooleanLiteral(true));
			case "map ENTRIES" -> assign(new Expression.Index(into, name(key)), name(value));
			default -> throw Rejection.notYet(source, "Adding a " + simpleName(unit().type(source)) + " to a " + target);
		};
		List<String> names = switch (holding.contents()) {
			case ELEMENTS, VALUES -> List.of("_", value);
			case KEYS -> List.of(key);
			case ENTRIES -> List.of(key, value);
		};
		String iterator = holding.contents() == Contents.ELEMENTS ? "ipairs" : "pairs";
		return List.of(new Statement.GenericFor(names, List.of(call(name(iterator), table)), Block.of(stored)));
	}

	// ---- Printing

	private boolean isSystemOut(ExpressionTree receiver) {
		return receiver instanceof MemberSelectTree select && select.getIdentifier().contentEquals("out")
				&& select.getExpression() instanceof IdentifierTree system
				&& system.getName().contentEquals("System");
	}

	private Expression print(MethodInvocationTree call, String name, List<? extends ExpressionTree> arguments) {
		if (!name.equals("println")) {
			throw Rejection.notYet(call, "System.out." + name);
		}
		return arguments.isEmpty()
				? call(name("print"), Expression.string(""))
				: call(name("print"), body.strings().asText(arguments.getFirst()));
	}

	// ---- Shared pieces

	/** A value stored in a list, map or set: never a literal null, because Lua tables cannot hold nil. */
	private Expression element(ExpressionTree value) {
		if (value instanceof LiteralTree literal && literal.getKind() == Tree.Kind.NULL_LITERAL) {
			throw new Rejection(value, "Lua tables cannot hold nil, so a list, map or set cannot store null.");
		}
		return body.expression(value);
	}

	/**
	 * Map keys and set elements are compared by identity in Lua, so neither a plugin class that defines value equality
	 * nor a value the client hands out can be one. The client's values are proxies, a new one each time a value is
	 * read: two reads of {@code config.Inv.INV}, of the player's entity or of a UI component are equal under
	 * {@code ==}, which the client defines, but are two keys (checked in the client, 2026-10-22). An API enum's
	 * constants are plain Lua values, which make good keys.
	 */
	private void checkKey(ExpressionTree key) {
		TypeMirror type = unit().type(key);
		TypeElement plugin = types().pluginType(type);
		if (plugin != null && (JavaTypes.isRecord(plugin) || plugin.getEnclosedElements().stream()
				.anyMatch(member -> member.getKind() == ElementKind.METHOD && member.getSimpleName().contentEquals("equals")))) {
			throw new Rejection(key, "Lua compares table keys by identity, so value-equal keys would not match; key the"
					+ " map by a string or number.");
		}
		if (types().isApiType(type) && ((DeclaredType) type).asElement() instanceof TypeElement api
				&& api.getKind() != ElementKind.ENUM) {
			String luaName = types().apiTypeName(api);
			throw new Rejection(key, "The client hands out a new " + (luaName == null ? api.getSimpleName() : luaName)
					+ " object each time one is read, and Lua compares table keys by identity, so the map or set would not"
					+ " find it again; key it by a number or string the value carries, such as its id or uid.");
		}
	}

	/** A receiver used twice in the Lua, as in {@code list[#list + 1] = x}, which therefore must not contain a call. */
	private Expression stableReceiver(ExpressionTree receiver) {
		if (receiver instanceof MethodInvocationTree || receiver instanceof NewClassTree) {
			throw new Rejection(receiver, "Lua needs this value twice; store it in a local first.");
		}
		return body.expression(receiver);
	}

	private static Expression length(Expression table) {
		return new Expression.Unary(UnaryOperator.LENGTH, table);
	}

	private static Expression nextIndex(Expression list) {
		return binary(length(list), BinaryOperator.ADD, integer(1));
	}

	private static Statement assign(Expression target, Expression value) {
		return new Statement.Assignment(List.of(target), List.of(value));
	}
}
