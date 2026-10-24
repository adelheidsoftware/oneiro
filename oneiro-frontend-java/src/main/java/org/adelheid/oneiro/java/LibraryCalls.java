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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.adelheid.oneiro.lua.BinaryOperator;
import org.adelheid.oneiro.lua.Block;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.Statement;
import org.adelheid.oneiro.lua.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * Calls of the JDK classes a plugin may use, as listed in docs/java-subset.md, and their Lua forms. This class picks
 * the form by the receiver's type and handles the calls that become statements, {@code Optional}, enum values,
 * {@code StringBuilder}, {@code System.out} and the constructors of the collections. {@link StringMethods},
 * {@link NumberMethods} and {@link CollectionMethods} hold the methods of strings, numbers and collections. Lists,
 * maps and sets are plain Lua tables, so they pass to and from the RS3 API unchanged. Anything else is rejected as
 * not supported yet.
 */
final class LibraryCalls {

	static final String STATEMENT_ONLY = "In Lua %s is a statement, not a value; call it on its own line.";
	private static final String NO_ITERATOR = "Lua walks tables with its for loop, not iterator objects; loop over the"
			+ " collection itself, or use removeIf to remove while walking.";
	static final String NO_CHAR_STREAM = "A Lua string has no stream of its characters; loop over"
			+ " text.toCharArray(), which walks the text itself.";
	static final String STREAM_IN_STATEMENT = "In Lua a stream is a loop, which runs as statements; make the"
			+ " stream the whole value of a local, an assignment or a return, or a forEach statement of its own.";

	final BodyLowering body;
	private final StringMethods stringMethods;
	private final NumberMethods numberMethods;
	private final CollectionMethods collections;

	LibraryCalls(BodyLowering body) {
		this.body = body;
		this.stringMethods = new StringMethods(this);
		this.numberMethods = new NumberMethods(this);
		this.collections = new CollectionMethods(this);
	}

	private JavaTypes types() {
		return body.types();
	}

	private UnitLowering unit() {
		return body.unit;
	}

	NumberMethods numberMethods() {
		return numberMethods;
	}

	StringMethods stringMethods() {
		return stringMethods;
	}

	/**
	 * The statements for a call whose Lua form is a statement rather than an expression, such as {@code list.add(x)},
	 * {@code map.put(k, v)}, {@code forEach}, {@code removeIf} and each {@code append} of a {@code StringBuilder}.
	 * Null when the call has an expression form, which {@link #invocation} gives.
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
		boolean collection = types().isList(receiverType) || types().isSet(receiverType)
				|| types().isDeque(receiverType);
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
					return List.of(insertedFirst(receiverTree, arguments.getFirst()));
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
					return List.of(Statement.assignment(body.operators().listElement(list, arguments.getFirst()),
							element(arguments.get(1))));
				}
				case "addAll" -> {
					if (arguments.size() == 1) {
						return collections.addedAll(receiverTree, CollectionMethods.Kind.LIST, arguments.getFirst());
					}
				}
				case "addFirst" -> {
					return List.of(insertedFirst(receiverTree, arguments.getFirst()));
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
					Expression stored = element(arguments.get(1));
					return List.of(Statement.assignment(entry(receiverTree, arguments.getFirst()), stored));
				}
				case "remove" -> {
					checkKey(arguments.getFirst());
					return List.of(Statement.assignment(entry(receiverTree, arguments.getFirst()),
							new Expression.Nil()));
				}
				case "putIfAbsent" -> {
					checkKey(arguments.getFirst());
					Expression stored = new Expression.Index(stableReceiver(receiverTree),
							stableReceiver(arguments.getFirst()));
					Expression absent = binary(stored, BinaryOperator.EQUAL, new Expression.Nil());
					Block store = Block.of(Statement.assignment(stored, element(arguments.get(1))));
					return List.of(Statement.ifThen(absent, store));
				}
				case "merge" -> {
					checkKey(arguments.getFirst());
					Expression sum = summed(receiverTree, arguments);
					if (sum != null) {
						Expression stored = new Expression.Index(stableReceiver(receiverTree),
								stableReceiver(arguments.getFirst()));
						return List.of(Statement.assignment(stored, sum));
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
					return collections.addedAll(receiverTree, CollectionMethods.Kind.MAP, arguments.getFirst());
				}
				default -> {
				}
			}
		}
		if (types().isSet(receiverType)) {
			switch (method) {
				case "add" -> {
					checkKey(arguments.getFirst());
					Expression member =
							new Expression.Index(body.expression(receiverTree), element(arguments.getFirst()));
					return List.of(Statement.assignment(member, new Expression.BooleanLiteral(true)));
				}
				case "remove" -> {
					checkKey(arguments.getFirst());
					return List.of(Statement.assignment(entry(receiverTree, arguments.getFirst()),
							new Expression.Nil()));
				}
				case "addAll" -> {
					return collections.addedAll(receiverTree, CollectionMethods.Kind.SET, arguments.getFirst());
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
			Expression part = body.strings().asText(value);
			statements.add(Statement.assignment(new Expression.Index(builder, nextIndex(builder)), part));
		}
		return statements;
	}

	/**
	 * {@code list[#list + 1] = x}, or {@code table.insert(list, x)} for a list from a call, which Lua must not make
	 * twice.
	 */
	private Statement appended(ExpressionTree receiver, ExpressionTree value) {
		if (receiver instanceof MethodInvocationTree || receiver instanceof NewClassTree) {
			return new Statement.CallStatement(call(field(name("table"), "insert"), body.expression(receiver),
					element(value)));
		}
		Expression list = body.expression(receiver);
		return Statement.assignment(new Expression.Index(list, nextIndex(list)), element(value));
	}

	/** {@code table.insert(list, 1, x)}, which moves the other elements up. */
	private Statement insertedFirst(ExpressionTree receiver, ExpressionTree value) {
		return new Statement.CallStatement(call(field(name("table"), "insert"), body.expression(receiver), integer(1),
				element(value)));
	}

	/** {@code map[key]} or {@code set[element]}, as the target of an assignment. */
	private Expression entry(ExpressionTree table, ExpressionTree key) {
		return new Expression.Index(body.expression(table), body.expression(key));
	}

	/**
	 * {@code list.forEach(x -> ...)} as the loop a for-each statement would be: the lambda's code is the loop's body
	 * and its parameters the loop's variables; any other function is called for each element.
	 */
	private List<Statement> forEachLoop(ExpressionTree receiver, TypeMirror receiverType, ExpressionTree function) {
		boolean keyed = types().isMap(receiverType) || types().isSet(receiverType);
		Expression iterator = call(name(keyed ? "pairs" : "ipairs"), body.expression(receiver));
		List<String> names = new ArrayList<>();
		if (!keyed) {
			names.add("_");
		}
		Lambdas.InlinedLambda inlined = body.lambdas().inline(function);
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
		Statement each = new Statement.CallStatement(new Expression.Call(called, arguments));
		lowered.add(new Statement.GenericFor(names, List.of(iterator), Block.of(each)));
		return lowered;
	}

	/**
	 * {@code removeIf(x -> test)}: on a list, a loop from the end, which removes as it goes without skipping an
	 * element; on a set, or a map's {@code keySet()} or {@code values()}, a loop that clears the entries that pass.
	 * Null for anything else.
	 */
	private @Nullable List<Statement> removeIf(ExpressionTree receiver, TypeMirror receiverType, ExpressionTree test) {
		List<Statement> lowered = new ArrayList<>();
		Lambdas.InlinedLambda inlined = body.lambdas().inline(test);
		if (inlined != null && inlined.value() == null) {
			throw new Rejection(test, "removeIf's test is a condition; write it as an expression, x -> condition.");
		}
		Expression action = inlined == null ? callable(test, lowered) : null;
		if (types().isList(receiverType) || types().isDeque(receiverType)) {
			Expression list = stableReceiver(receiver);
			String index = unit().freshLocal("i", receiver);
			String item = inlined != null ? inlined.parameters().getFirst() : unit().freshLocal("item", receiver);
			Expression condition = condition(inlined, action, item);
			Statement remove = new Statement.CallStatement(call(field(name("table"), "remove"), list, name(index)));
			Block loopBody = Block.of(
					new Statement.Local(List.of(item), List.of(new Expression.Index(list, name(index)))),
					Statement.ifThen(condition, Block.of(remove)));
			lowered.add(new Statement.NumericFor(index, length(list), integer(1), integer(-1), loopBody));
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
		Statement clear = Statement.ifThen(condition(inlined, action, item),
				Block.of(Statement.assignment(cleared, new Expression.Nil())));
		lowered.add(new Statement.GenericFor(names, List.of(call(name("pairs"), table)), Block.of(clear)));
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
		Lambdas.InlinedLambda present = body.lambdas().inline(arguments.getFirst());
		@Nullable VariableTree parameter = null;
		if (present != null) {
			LambdaExpressionTree lambda = (LambdaExpressionTree) BodyLowering.withoutParentheses(arguments.getFirst());
			parameter = lambda.getParameters().getFirst();
		}
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
		} else if (value instanceof Expression.Name valueName
				&& valueName.name().equals(present.parameters().getFirst())) {
			then = present.body();
		} else {
			then = prepended(new Statement.Local(List.of(present.parameters().getFirst()), List.of(value)),
					present.body());
		}
		Block otherwise = null;
		if (arguments.size() == 2) {
			Lambdas.InlinedLambda absent = body.lambdas().inline(arguments.get(1));
			otherwise = absent != null
					? absent.body()
					: Block.of(new Statement.CallStatement(
							new Expression.Call(callable(arguments.get(1), lowered), List.of())));
		}
		lowered.add(new Statement.If(List.of(new Statement.If.Branch(
				binary(value, BinaryOperator.NOT_EQUAL, new Expression.Nil()), then)), otherwise));
		return lowered;
	}

	/** The block with a statement before its first one. */
	static Block prepended(Statement first, Block block) {
		List<Block.Entry> entries = new ArrayList<>();
		entries.add(Block.Entry.of(first));
		entries.addAll(block.entries());
		return new Block(entries, block.closingComments());
	}

	/** A predicate's test of one element: the lambda's own expression, or a call of the function on the element. */
	private Expression condition(Lambdas.@Nullable InlinedLambda inlined, @Nullable Expression action, String item) {
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
	Expression orDefault(Expression value, boolean canBeFalse, ExpressionTree fallback) {
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
	Expression mapDefault(Helper helper, ExpressionTree receiver, List<? extends ExpressionTree> arguments) {
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
	 * The edits of a {@code StringBuilder}, a list of pieces, that need its whole text: each makes the text and puts
	 * the changed text back with {@code builderSet}. Null for any other method.
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
			Expression reversed = methodCall(whole, "reverse");
			return List.of(new Statement.CallStatement(Helper.BUILDER_SET.invoke(builder, reversed)));
		}
		// The others cut the text at a position, so it goes into a local first
		String local = body.invent("text", receiver);
		Expression text = name(local);
		Expression position = body.expression(arguments.getFirst());
		Expression before = methodCall(text, "sub", integer(1), position);
		Expression changed = switch (method) {
			case "insert" -> concatenation(before, body.strings().asText(arguments.get(1)),
					methodCall(text, "sub", IndexArithmetic.plusOne(position)));
			case "deleteCharAt" ->
					concatenation(before, methodCall(text, "sub", binary(position, BinaryOperator.ADD, integer(2))));
			case "delete" -> concatenation(before,
					methodCall(text, "sub", IndexArithmetic.plusOne(body.expression(arguments.get(1)))));
			// setCharAt
			default -> concatenation(before, body.expression(arguments.get(1)),
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
	static @Nullable String plainSeparator(ExpressionTree regex) {
		if (!(regex instanceof LiteralTree literal) || !(literal.getValue() instanceof String pattern)
				|| pattern.isEmpty()) {
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

	/** A call with an expression form, picked by the receiver's type or, for a static method, by its class. */
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
				return stringMethods.stringMethod(call, name, body.expression(receiver), arguments);
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
				return collections.listMethod(call, name, receiver, arguments);
			}
			if (types().isMap(receiverType)) {
				return collections.mapMethod(call, name, receiver, arguments);
			}
			if (types().isSet(receiverType)) {
				return collections.setMethod(call, name, receiver, arguments);
			}
			if (types().isDeque(receiverType)) {
				return collections.dequeMethod(call, name, body.expression(receiver), arguments);
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
					case "charAt" -> stringMethods.characterAt(whole, arguments.getFirst());
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
			if (numberMethods.isBoxed(receiverType)) {
				return numberMethods.boxedMethod(call, name, body.expression(receiver), arguments);
			}
			if (name.equals("equals") && arguments.size() == 1) {
				// Object.equals, not overridden by the plugin class: identity, as Lua's == compares tables
				return binary(body.expression(receiver), BinaryOperator.EQUAL, body.expression(arguments.getFirst()));
			}
			if (name.equals("hashCode") || name.equals("getClass")) {
				throw new Rejection(call, "Lua has no runtime type information to match " + name + "().");
			}
			// When the receiver is itself unsupported, as names.stream() is in names.stream().count(), report that
			body.expression(receiver);
			throw Rejection.notYet(call, "Calling " + name + " on " + simpleName(receiverType));
		}
		return switch (owner) {
			case "java.lang.Math" -> numberMethods.mathMethod(call, name, arguments);
			// name() or ordinal() in an enum's own code, which only an object enum has
			case "java.lang.Enum" -> enumMethod(call, name, body.self(call), true, arguments);
			case "java.lang.Character" -> numberMethods.characterStatic(call, name, arguments);
			case "java.util.Optional", "java.util.OptionalInt", "java.util.OptionalLong", "java.util.OptionalDouble" ->
					switch (name) {
						case "of", "ofNullable" -> body.expression(arguments.getFirst());
						case "empty" -> new Expression.Nil();
						default -> notYet(call, simpleName(unit().type(call)) + "." + name);
					};
			case "java.lang.String" -> stringMethods.stringStatic(call, name, arguments);
			case "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte", "java.lang.Double",
					"java.lang.Float", "java.lang.Boolean" -> numberMethods.boxedStatic(call, name, owner, arguments);
			case "java.util.List" -> name.equals("of") ? positional(arguments) : notYet(call, "List." + name);
			case "java.util.Set" -> name.equals("of") ? collections.setOf(arguments) : notYet(call, "Set." + name);
			case "java.util.Map" -> name.equals("of") ? collections.mapOf(arguments) : notYet(call, "Map." + name);
			case "java.util.Collections" -> {
				if (!name.equals("sort")) {
					throw Rejection.notYet(call, "Collections." + name);
				}
				@Nullable ExpressionTree comparator = arguments.size() > 1 ? arguments.get(1) : null;
				yield collections.sort(body.expression(arguments.getFirst()), comparator);
			}
			default -> notYet(call, "Calling " + owner + "." + name);
		};
	}

	private static Expression notYet(Tree tree, String construct) {
		throw Rejection.notYet(tree, construct);
	}

	/** A type as a message names it: {@code Stream} rather than {@code java.util.stream.Stream<java.lang.String>}. */
	static String simpleName(TypeMirror type) {
		return type instanceof DeclaredType declared
				? declared.asElement().getSimpleName().toString()
				: type.toString();
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
				yield binary(value, BinaryOperator.OR, body.exceptions().errorCall(creation));
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

	// ---- Creating lists, maps, sets and builders

	/** {@code List.of(a, b)} as the table {@code { a, b }}. */
	Expression positional(List<? extends ExpressionTree> elements) {
		return new Expression.Table(elements.stream()
				.map(element -> (Expression.TableField) new Expression.TableField.Positional(element(element)))
				.toList());
	}

	/** {@code new ArrayList<>()}, {@code new HashMap<>()}, {@code new HashSet<>()} and {@code new StringBuilder()}. */
	Expression construct(NewClassTree creation, TypeElement type) {
		String name = type.getQualifiedName().toString();
		List<? extends ExpressionTree> arguments = creation.getArguments();
		// new StringBuilder('a') takes the char as its number, a capacity, as Java does
		boolean capacityOnly = arguments.isEmpty()
				|| (arguments.size() == 1 && (unit().type(arguments.getFirst()).getKind() == TypeKind.INT
						|| stringMethods.isCharType(arguments.getFirst())));
		return switch (name) {
			case "java.util.ArrayList", "java.util.LinkedList", "java.util.ArrayDeque" -> capacityOnly
					? new Expression.Table(List.of())
					: collections.listCopy(creation, arguments.getFirst());
			case "java.util.HashMap", "java.util.HashSet" -> capacityOnly
					? new Expression.Table(List.of())
					: collections.filled(creation, new Expression.Table(List.of()), setOrMap(name),
							arguments.getFirst());
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
				if (arguments.size() == 1 && stringMethods.isCharArray(arguments.getFirst())) {
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
						: collections.filled(creation, empty, setOrMap(name), arguments.getFirst());
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
					throw new Rejection(argument, "Java copies a sorted map or set with its comparator, which a Lua"
							+ " table does not keep; make the new one with that comparator, then add the entries.");
				}
				yield collections.filled(creation, Helper.SORTED_TABLE.invoke(), setOrMap(name), argument);
			}
			default -> {
				if (types().isThrowable(type.asType())) {
					// An exception kept for later is its message, the value error raises
					yield body.exceptions().errorMessage(creation);
				}
				throw Rejection.notYet(creation, "Creating " + type.getSimpleName());
			}
		};
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
	Expression element(ExpressionTree value) {
		if (value instanceof LiteralTree literal && literal.getKind() == Tree.Kind.NULL_LITERAL) {
			throw new Rejection(value, "Lua tables cannot hold nil, so a list, map or set cannot store null.");
		}
		return body.expression(value);
	}

	/**
	 * Map keys and set elements are compared by identity in Lua, so neither a plugin class that defines value equality
	 * nor a value the client hands out can be one. The client's values are proxies, a new one each time a value is
	 * read: two reads of {@code config.Inv.INV}, of the player's entity or of a UI component are equal under
	 * {@code ==}, which the client defines, but index a table as two different keys. An API enum's constants are plain
	 * Lua values, which make good keys.
	 */
	void checkKey(ExpressionTree key) {
		TypeMirror type = unit().type(key);
		TypeElement plugin = types().pluginType(type);
		if (plugin != null && (JavaTypes.isRecord(plugin) || declaresEquals(plugin))) {
			throw new Rejection(key, "Lua compares table keys by identity, so value-equal keys would not match; key the"
					+ " map by a string or number.");
		}
		if (types().isApiType(type) && ((DeclaredType) type).asElement() instanceof TypeElement api
				&& api.getKind() != ElementKind.ENUM) {
			String luaName = types().apiTypeName(api);
			throw new Rejection(key, "The client hands out a new " + (luaName == null ? api.getSimpleName() : luaName)
					+ " object each time one is read, and Lua compares table keys by identity, so the map or set would"
					+ " not find it again; key it by a number or string the value carries, such as its id or uid.");
		}
	}

	/** The kind of collection a set or map class makes, by its name: {@code java.util.HashSet} makes a set. */
	private static CollectionMethods.Kind setOrMap(String className) {
		return className.endsWith("Set") ? CollectionMethods.Kind.SET : CollectionMethods.Kind.MAP;
	}

	private static boolean declaresEquals(TypeElement type) {
		return type.getEnclosedElements().stream()
				.anyMatch(member -> member.getKind() == ElementKind.METHOD
						&& member.getSimpleName().contentEquals("equals"));
	}

	/** {@code Integer}, {@code Long} or {@code Double}, whose {@code sum} and {@code compare} are Lua operators. */
	static boolean isNumberBox(TypeElement type) {
		String name = type.getQualifiedName().toString();
		return name.equals("java.lang.Integer") || name.equals("java.lang.Long") || name.equals("java.lang.Double");
	}

	/** A receiver used twice in the Lua, as in {@code list[#list + 1] = x}, which therefore must not contain a call. */
	Expression stableReceiver(ExpressionTree receiver) {
		if (receiver instanceof MethodInvocationTree || receiver instanceof NewClassTree) {
			throw new Rejection(receiver, "Lua needs this value twice; store it in a local first.");
		}
		return body.expression(receiver);
	}

	static Expression length(Expression table) {
		return new Expression.Unary(UnaryOperator.LENGTH, table);
	}

	static Expression nextIndex(Expression list) {
		return binary(length(list), BinaryOperator.ADD, integer(1));
	}
}
