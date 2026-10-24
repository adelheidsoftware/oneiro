package org.adelheid.oneiro.java;

import static org.adelheid.oneiro.lua.Expression.binary;
import static org.adelheid.oneiro.lua.Expression.call;
import static org.adelheid.oneiro.lua.Expression.integer;
import static org.adelheid.oneiro.lua.Expression.name;

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssertTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ContinueTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EmptyStatementTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.LabeledStatementTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.tree.YieldTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.adelheid.oneiro.lua.BinaryOperator;
import org.adelheid.oneiro.lua.Block;
import org.adelheid.oneiro.lua.Comment;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.NameUses;
import org.adelheid.oneiro.lua.SourcePosition;
import org.adelheid.oneiro.lua.Statement;
import org.adelheid.oneiro.lua.Trivia;
import org.adelheid.oneiro.lua.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * Lowers the statements and expressions of one method, constructor or initializer into the Lua tree, as
 * docs/java-subset.md describes. This class lowers blocks, local variables, assignments, names, field reads and calls,
 * and keeps the scope the rest share: whether {@code this} exists, which integers are known to be non-negative, the
 * variables that stand for other values, and the locals the Lua adds. Every other kind of code has a class of its own,
 * reached through this one: loops, switches, exceptions, lambdas, operators, type tests, object creation, calls of the
 * plugin's methods, of the client's API and of the Java library, string building, streams and comparators.
 */
final class BodyLowering {

	private static final Expression SELF = name("self");

	final UnitLowering unit;
	final boolean hasSelf;
	final boolean inConstructor;
	// Whether self is an anonymous class's object, whose methods cannot read the outer object's members
	boolean anonymousObject;

	private final LibraryCalls library;
	private final ApiCalls api;
	private final StringBuilding strings;
	private final StreamLoops streams;
	private final Comparators comparators;
	private final MethodReferences methodReferences;
	private final SourceFacts facts;
	private final Lambdas lambdas;
	private final Switches switches;
	private final Loops loops;
	private final Exceptions exceptions;
	private final TypeTests typeTests;
	private final ObjectCreation objectCreation;
	private final Operators operators;
	private final PluginCalls pluginCalls;

	// Integer variables known never to be negative, so that dividing them can use Lua's //
	final Set<Element> nonNegative = new HashSet<>();
	// Map.Entry loop variables and the Lua names of their key and value, for for (var e : map.entrySet())
	final Map<Element, List<String>> entries = new HashMap<>();
	// Locals holding a Lua function's several return values, and the Lua local of each record component by accessor
	private final Map<Element, Map<String, String>> returnValues = new HashMap<>();
	// The parameters of the catch clauses being lowered, which stand for the error value pcall hands back
	final Set<Element> caughtErrors = new HashSet<>();
	// The Lua locals this code introduces that the Java does not have, so that two of them never share a name
	private final Set<String> invented = new HashSet<>();
	// Variables that stand for a value of the code around them: a stream lambda's parameter for the loop's element, a
	// pattern's variable for the value it tests
	private final Map<Element, Expression> aliases = new HashMap<>();

	/**
	 * @param hasSelf whether the code runs with an instance, so that {@code this} is {@code self}
	 * @param inConstructor whether {@code return;} must return {@code self}, as at the end of {@code new}
	 */
	BodyLowering(UnitLowering unit, boolean hasSelf, boolean inConstructor) {
		this.unit = unit;
		this.hasSelf = hasSelf;
		this.inConstructor = inConstructor;
		this.library = new LibraryCalls(this);
		this.api = new ApiCalls(this);
		this.strings = new StringBuilding(this);
		this.streams = new StreamLoops(this);
		this.comparators = new Comparators(this);
		this.methodReferences = new MethodReferences(this);
		this.facts = new SourceFacts(this);
		this.lambdas = new Lambdas(this);
		this.switches = new Switches(this);
		this.loops = new Loops(this);
		this.exceptions = new Exceptions(this);
		this.typeTests = new TypeTests(this);
		this.objectCreation = new ObjectCreation(this);
		this.operators = new Operators(this);
		this.pluginCalls = new PluginCalls(this);
	}

	ApiCalls api() {
		return api;
	}

	StringBuilding strings() {
		return strings;
	}

	LibraryCalls library() {
		return library;
	}

	Comparators comparators() {
		return comparators;
	}

	MethodReferences methodReferences() {
		return methodReferences;
	}

	SourceFacts facts() {
		return facts;
	}

	Lambdas lambdas() {
		return lambdas;
	}

	Loops loops() {
		return loops;
	}

	Exceptions exceptions() {
		return exceptions;
	}

	TypeTests typeTests() {
		return typeTests;
	}

	Operators operators() {
		return operators;
	}

	JavaTypes types() {
		return unit.types;
	}

	// ---- Blocks and comments

	Block block(BlockTree block) {
		return statements(block.getStatements(), unit.start(block) + 1, unit.end(block) - 1);
	}

	/** A loop or branch body: a block, or a single statement standing in for one. */
	Block body(StatementTree body) {
		if (body instanceof BlockTree block) {
			return block(block);
		}
		return statements(List.of(body), unit.start(body), unit.end(body));
	}

	/**
	 * Statements with the comments around them: the comments before a statement lead it, a comment after it on the
	 * same line trails it, and the comments after the last statement close the block. Blank lines between
	 * statements carry over. A rejected statement is reported, and lowering goes on with the next one.
	 */
	Block statements(List<? extends StatementTree> statements, int from, int to) {
		List<Block.Entry> lowered = new ArrayList<>();
		List<Comment> pending = new ArrayList<>();
		int cursor = from;
		for (StatementTree statement : statements) {
			List<CommentIndex.SourceComment> leading = unit.comments.take(cursor, unit.start(statement));
			int firstStart = leading.isEmpty() ? unit.start(statement) : leading.getFirst().start();
			boolean blankLine = !lowered.isEmpty() && unit.comments.hasBlankLine(cursor, firstStart);
			pending.addAll(comments(leading));
			List<Block.Entry> parts;
			try {
				parts = entries(statement);
			} catch (Rejection rejection) {
				unit.reject(rejection);
				parts = List.of();
			}
			cursor = unit.end(statement);
			CommentIndex.SourceComment trailing = unit.comments.takeTrailing(cursor);
			if (trailing != null) {
				cursor = trailing.end();
			}
			SourcePosition origin = unit.origin(statement);
			for (int index = 0; index < parts.size(); index++) {
				Block.Entry part = parts.get(index);
				boolean first = index == 0;
				boolean last = index == parts.size() - 1;
				// The statement's comments go around its Lua; a part from one of its own blocks keeps its own
				List<Comment> above = new ArrayList<>(first ? pending : List.of());
				above.addAll(part.trivia().leading());
				Trivia trivia = new Trivia(
						first ? blankLine : part.trivia().blankLineBefore(),
						above,
						last && trailing != null ? String.join(" ", trailing.lines()) : part.trivia().trailing());
				lowered.add(new Block.Entry(part.statement(), trivia, part.origin() != null ? part.origin() : origin));
			}
			if (!parts.isEmpty()) {
				pending.clear();
			}
		}
		pending.addAll(comments(unit.comments.take(cursor, to)));
		return new Block(foldFields(lowered), pending);
	}

	/**
	 * {@code local t = {}} followed by {@code t.a = x} and {@code t.b = y} as one constructor,
	 * {@code local t = { a = x, b = y }}, the way a table the plugin fills in for the API reads in Lua. Only field
	 * assignments right after it fold, without comments or blank lines between them, each field once, and only values
	 * that do not mention the table, which a constructor could not see yet.
	 */
	private static List<Block.Entry> foldFields(List<Block.Entry> entries) {
		List<Block.Entry> folded = new ArrayList<>();
		for (int index = 0; index < entries.size(); index++) {
			Block.Entry entry = entries.get(index);
			if (!(entry.statement() instanceof Statement.Local local) || local.names().size() != 1
					|| local.values().size() != 1 || !(local.values().getFirst() instanceof Expression.Table empty)
					|| !empty.fields().isEmpty() || entry.trivia().trailing() != null) {
				folded.add(entry);
				continue;
			}
			String table = local.names().getFirst();
			List<Expression.TableField> fields = new ArrayList<>();
			Set<String> keys = new HashSet<>();
			int next = index + 1;
			for (; next < entries.size(); next++) {
				Block.Entry candidate = entries.get(next);
				Trivia trivia = candidate.trivia();
				if (!trivia.leading().isEmpty() || trivia.blankLineBefore() || trivia.trailing() != null
						|| !(candidate.statement() instanceof Statement.Assignment assignment)
						|| assignment.targets().size() != 1 || assignment.values().size() != 1
						|| !(assignment.targets().getFirst() instanceof Expression.Field field)
						|| !(field.target() instanceof Expression.Name target) || !target.name().equals(table)
						|| NameUses.mentions(assignment.values().getFirst(), table) || !keys.add(field.name())) {
					break;
				}
				fields.add(new Expression.TableField.Named(field.name(), assignment.values().getFirst()));
			}
			if (fields.isEmpty()) {
				folded.add(entry);
				continue;
			}
			folded.add(new Block.Entry(new Statement.Local(local.names(), List.of(new Expression.Table(fields))),
					entry.trivia(), entry.origin()));
			index = next - 1;
		}
		return folded;
	}

	static List<Comment> comments(List<CommentIndex.SourceComment> sourceComments) {
		List<Comment> lines = new ArrayList<>();
		for (CommentIndex.SourceComment comment : sourceComments) {
			comment.lines().forEach(line -> lines.add(Comment.line(line)));
		}
		return lines;
	}

	// ---- Statements

	/**
	 * A statement's Lua as block entries. A try statement's entries include the statements of its finally block,
	 * with their comments; every other statement's parts carry none of their own.
	 */
	private List<Block.Entry> entries(StatementTree statement) {
		if (statement instanceof TryTree attempt) {
			return exceptions.tryStatement(attempt);
		}
		return statement(statement).stream().map(Block.Entry::of).toList();
	}

	List<Statement> statement(StatementTree statement) {
		return switch (statement) {
			case VariableTree variable -> localVariable(variable);
			case ExpressionStatementTree expression -> expressionStatement(expression.getExpression());
			case IfTree branch -> List.of(ifChain(branch));
			case WhileLoopTree loop ->
					List.of(new Statement.While(expression(loop.getCondition()),
							loops.loopBody(loop.getStatement(), List.of())));
			case DoWhileLoopTree loop -> List.of(new Statement.Repeat(
					loops.loopBody(loop.getStatement(), List.of()),
					new Expression.Unary(UnaryOperator.NOT, expression(loop.getCondition()))));
			case ForLoopTree loop -> loops.forLoop(loop);
			case EnhancedForLoopTree loop -> List.of(loops.forEach(loop));
			case BreakTree jump -> {
				if (jump.getLabel() != null) {
					throw labelledJump(jump);
				}
				yield List.of(new Statement.Break());
			}
			case ContinueTree jump -> List.of(loops.continueStatement(jump));
			case ReturnTree returned -> returnStatement(returned);
			case ThrowTree thrown -> List.of(exceptions.throwStatement(thrown));
			case BlockTree nested -> List.of(new Statement.Do(block(nested)));
			case EmptyStatementTree _ -> List.of();
			case SwitchTree switched ->
					switches.switchChain(switched, switched.getExpression(), switched.getCases(), null);
			case YieldTree yielded -> switches.yieldStatement(yielded);
			case TryTree attempt ->
					throw new IllegalStateException("a try statement outside a block at " + unit.origin(attempt));
			case SynchronizedTree synchronizedBlock -> throw new Rejection(synchronizedBlock,
					"Plugins run on one thread, and Lua has no threads to synchronise; remove the synchronized block.");
			case AssertTree assertion -> throw new Rejection(assertion,
					"Java assertions are off by default, but Lua's assert always runs; write"
							+ " if (!condition) throw new IllegalStateException(\"...\") instead.");
			case LabeledStatementTree labelled -> throw labelledJump(labelled);
			case ClassTree local -> unit.localClass(local);
			default -> throw Rejection.notYet(statement, "This kind of statement (" + statement.getKind() + ")");
		};
	}

	static Rejection labelledJump(Tree tree) {
		return new Rejection(tree, "Lua's break and continue only reach the innermost loop; move the loops into a"
				+ " method and return from it.");
	}

	List<Statement> localVariable(VariableTree variable) {
		ExpressionTree initializer = variable.getInitializer();
		if (initializer != null && types().isReturnValues(unit.type(variable))) {
			return returnValuesLocal(variable, initializer);
		}
		String luaName = declare(variable);
		if (initializer == null) {
			return List.of(new Statement.Local(List.of(luaName), List.of()));
		}
		StreamLoops.Pipeline pipeline = streams.pipeline(initializer);
		if (pipeline != null) {
			// The stream's result is built in the local itself
			return streams.lower(pipeline, new StreamLoops.Destination.Local(luaName));
		}
		if (needsStatements(initializer)) {
			List<Statement> lowered = new ArrayList<>();
			lowered.add(new Statement.Local(List.of(luaName), List.of()));
			lowered.addAll(deliver(initializer, value -> Statement.assignment(name(luaName), value)));
			return lowered;
		}
		// A local that starts non-negative and is only raised, in the block it lives in, stays non-negative
		Element element = unit.element(variable);
		if (facts.knownNonNegative(initializer) && (variable.getModifiers().getFlags().contains(Modifier.FINAL)
				|| facts.onlyRaises(unit.path(variable).getParentPath().getLeaf(), element))) {
			nonNegative.add(element);
		}
		return List.of(new Statement.Local(List.of(luaName), List.of(expression(initializer))));
	}

	/**
	 * {@code var ground = World.GetGroundHeight(level, x, z);} as one Lua local per value,
	 * {@code local height, available = World.GetGroundHeight(level, x, z)}: each local is named after its record
	 * component, or after the Java local and the component where that name is taken.
	 */
	private List<Statement> returnValuesLocal(VariableTree variable, ExpressionTree initializer) {
		if (!(withoutParentheses(initializer) instanceof MethodInvocationTree)) {
			throw new Rejection(initializer, "The values a Lua function returns exist only at its call; initialise "
					+ variable.getName() + " with the call itself.");
		}
		TypeElement record = (TypeElement) ((DeclaredType) unit.type(variable)).asElement();
		Element element = unit.element(variable);
		Set<String> read = readComponents(variable, element);
		List<? extends RecordComponentElement> recordComponents = record.getRecordComponents();
		// Values after the last one read are left off; unread ones before it are _
		int last = -1;
		for (int index = 0; index < recordComponents.size(); index++) {
			if (read.contains(recordComponents.get(index).getAccessor().getSimpleName().toString())) {
				last = index;
			}
		}
		Map<String, String> components = new LinkedHashMap<>();
		List<String> locals = new ArrayList<>();
		for (int index = 0; index <= last; index++) {
			RecordComponentElement component = recordComponents.get(index);
			String accessor = component.getAccessor().getSimpleName().toString();
			if (!read.contains(accessor)) {
				locals.add("_");
				continue;
			}
			String local = componentLocal(variable, component.getSimpleName().toString());
			components.put(accessor, local);
			locals.add(local);
		}
		returnValues.put(element, components);
		Expression call = expression(initializer);
		if (locals.isEmpty()) {
			return List.of(new Statement.CallStatement(call));
		}
		return List.of(new Statement.Local(locals, List.of(call)));
	}

	/** A component's local: its own name, or the Java local's name and its name where that is taken. */
	private String componentLocal(VariableTree variable, String preferred) {
		String local = unit.freshLocal(preferred, variable);
		if (local.equals(unit.names.luaName(preferred)) && invented.add(local)) {
			return local;
		}
		String prefixed = variable.getName().toString() + Character.toUpperCase(preferred.charAt(0))
				+ preferred.substring(1);
		String candidate = unit.freshLocal(prefixed, variable);
		for (int suffix = 2; !invented.add(candidate); suffix++) {
			candidate = unit.freshLocal(prefixed + suffix, variable);
		}
		return candidate;
	}

	/**
	 * The accessors the code after a local's declaration calls on it, in the block that holds it; every one when the
	 * local is returned whole.
	 */
	private Set<String> readComponents(VariableTree variable, Element element) {
		Set<String> read = new HashSet<>();
		TreePath scope = unit.path(variable).getParentPath();
		TypeElement record = (TypeElement) ((DeclaredType) unit.type(variable)).asElement();
		new TreeScanner<Void, Void>() {
			@Override
			public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
				if (node.getMethodSelect() instanceof MemberSelectTree select
						&& select.getExpression() instanceof IdentifierTree receiver
						&& facts.refersTo(receiver, element)) {
					read.add(select.getIdentifier().toString());
				}
				return super.visitMethodInvocation(node, null);
			}

			@Override
			public Void visitReturn(ReturnTree node, Void unused) {
				ExpressionTree value = node.getExpression();
				if (value != null && facts.refersTo(withoutParentheses(value), element)) {
					record.getRecordComponents()
							.forEach(component -> read.add(component.getAccessor().getSimpleName().toString()));
				}
				return super.visitReturn(node, null);
			}
		}.scan(scope.getLeaf(), null);
		return read;
	}

	/** Makes a lambda's parameter, already declared, stand for a local of the code around the lambda. */
	void alias(VariableTree parameter, String luaName) {
		aliases.put(unit.element(parameter), name(luaName));
	}

	/** Makes a declared variable stand for a value, as a pattern's variable stands for the value it tests. */
	void alias(VariableTree variable, Expression value) {
		aliases.put(unit.element(variable), value);
	}

	/** The Lua name for a declared local or parameter, after checking that Lua can hold it. */
	String declare(VariableTree variable) {
		String javaName = variable.getName().toString();
		if (!LocalNames.isAscii(javaName)) {
			throw new Rejection(variable, "Lua names are ASCII letters, digits and underscores only; rename "
					+ javaName + ".");
		}
		aliases.remove(unit.element(variable));
		TypeMirror type = unit.type(variable);
		if (types().isReturnValues(type)) {
			throw new Rejection(variable, ((DeclaredType) type).asElement().getSimpleName() + " stands for several Lua"
					+ " values, which only a local can receive, from a call; pass its components one by one instead.");
		}
		return unit.codeLocalName(unit.element(variable), variable);
	}

	/** An expression used as a statement; usually one Lua statement, but each chained {@code append} is its own. */
	List<Statement> expressionStatement(ExpressionTree expression) {
		return switch (expression) {
			case AssignmentTree assignment -> {
				Expression target = assignable(assignment.getVariable());
				yield deliver(assignment.getExpression(), value -> Statement.assignment(target, value));
			}
			case CompoundAssignmentTree compound -> List.of(compoundAssignment(compound));
			case UnaryTree unary when isStep(unary.getKind()) -> {
				Expression target = assignable(unary.getExpression());
				BinaryOperator operator = unary.getKind() == Tree.Kind.POSTFIX_INCREMENT
						|| unary.getKind() == Tree.Kind.PREFIX_INCREMENT ? BinaryOperator.ADD : BinaryOperator.SUBTRACT;
				if (types().isChar(unit.type(unary.getExpression()))) {
					// c++ steps the char's number and stores a char again
					Expression stepped = binary(charCode(unary.getExpression(), target), operator, integer(1));
					Expression character = call(Expression.field(name("string"), "char"), stepped);
					yield List.of(Statement.assignment(target, character));
				}
				yield List.of(Statement.assignment(target, binary(target, operator, integer(1))));
			}
			case MethodInvocationTree call -> {
				StreamLoops.Pipeline pipeline = streams.pipeline(call);
				if (pipeline != null) {
					yield streams.lower(pipeline, new StreamLoops.Destination.Effect());
				}
				List<Statement> statements = library.statements(call);
				if (statements != null) {
					yield statements;
				}
				yield List.of(callStatement(call, expression(call)));
			}
			case NewClassTree creation -> List.of(callStatement(creation, expression(creation)));
			default -> throw Rejection.notYet(expression, "This expression statement (" + expression.getKind() + ")");
		};
	}

	private static Statement callStatement(Tree tree, Expression lowered) {
		if (lowered instanceof Expression.Call || lowered instanceof Expression.MethodCall) {
			return new Statement.CallStatement(lowered);
		}
		throw new Rejection(tree, "The result of this expression is unused, and its Lua form is not a call that can"
				+ " stand alone; use the result or remove the line.");
	}

	static boolean isStep(Tree.Kind kind) {
		return kind == Tree.Kind.POSTFIX_INCREMENT || kind == Tree.Kind.PREFIX_INCREMENT
				|| kind == Tree.Kind.POSTFIX_DECREMENT || kind == Tree.Kind.PREFIX_DECREMENT;
	}

	/** The Lua target of an assignment: a local, a field, or an array element. */
	private Expression assignable(ExpressionTree target) {
		Element assigned = unit.trees.getElement(unit.path(target));
		if (assigned != null && assigned.getKind() == ElementKind.FIELD
				&& types().configType(assigned.asType()) != null) {
			throw new Rejection(target, "A field of settings needs no value: every call through it calls the settings"
					+ " module, so remove the assignment.");
		}
		checkNotIntoCopy(target);
		if (assigned instanceof VariableElement field && field.getKind() == ElementKind.FIELD
				&& types().isApi(JavaTypes.owner(field))) {
			api.assigned(field, target);
		}
		return switch (target) {
			case IdentifierTree identifier -> identifier(identifier);
			case MemberSelectTree select -> memberSelect(select);
			case ArrayAccessTree access ->
					new Expression.Index(expression(access.getExpression()), operators.index(access.getIndex()));
			case ParenthesizedTree parenthesized -> assignable(parenthesized.getExpression());
			default -> throw Rejection.notYet(target,
					"Assigning to this kind of expression (" + target.getKind() + ")");
		};
	}

	/**
	 * Rejects changing part of a value the client hands out as a copy, {@code instance.translation.y = 5}: the copy is
	 * changed, and the client never sees it.
	 */
	private void checkNotIntoCopy(ExpressionTree target) {
		if (!(target instanceof MemberSelectTree select)) {
			return;
		}
		ExpressionTree holder = select.getExpression();
		while (holder instanceof ParenthesizedTree parenthesized) {
			holder = parenthesized.getExpression();
		}
		Element field = holder instanceof MemberSelectTree || holder instanceof IdentifierTree
				? unit.element(holder) : null;
		if (field != null && field.getKind() == ElementKind.FIELD
				&& JavaTypes.hasAnnotation(field, JavaTypes.LUA_COPY)) {
			String name = field.getSimpleName().toString();
			String type = field.asType() instanceof DeclaredType declared
					? declared.asElement().getSimpleName().toString()
					: field.asType().toString();
			String copy = type + " " + name + " = " + holder + "; " + name + "." + select.getIdentifier() + " = ...;";
			throw new Rejection(target, holder + " hands out a copy of the client's value, so changing " + name + "."
					+ select.getIdentifier() + " here changes nothing in the client; "
					+ (field.getModifiers().contains(Modifier.FINAL)
							? "change a local copy instead: " + copy
							: "change a local copy and assign it back: " + copy + " " + holder + " = " + name + ";"));
		}
	}

	private Statement compoundAssignment(CompoundAssignmentTree compound) {
		ExpressionTree targetTree = compound.getVariable();
		if (SourceFacts.containsCall(targetTree)) {
			throw new Rejection(targetTree, "Lua would evaluate this target twice; store the part with the call in a"
					+ " local first.");
		}
		Expression target = assignable(targetTree);
		if (compound.getKind() == Tree.Kind.PLUS_ASSIGNMENT && types().isString(unit.type(targetTree))) {
			Expression appended = strings.concatOperand(compound.getExpression());
			return Statement.assignment(target, binary(target, BinaryOperator.CONCAT, appended));
		}
		Tree.Kind kind = switch (compound.getKind()) {
			case PLUS_ASSIGNMENT -> Tree.Kind.PLUS;
			case MINUS_ASSIGNMENT -> Tree.Kind.MINUS;
			case MULTIPLY_ASSIGNMENT -> Tree.Kind.MULTIPLY;
			case DIVIDE_ASSIGNMENT -> Tree.Kind.DIVIDE;
			case REMAINDER_ASSIGNMENT -> Tree.Kind.REMAINDER;
			case AND_ASSIGNMENT -> Tree.Kind.AND;
			case OR_ASSIGNMENT -> Tree.Kind.OR;
			case XOR_ASSIGNMENT -> Tree.Kind.XOR;
			case LEFT_SHIFT_ASSIGNMENT -> Tree.Kind.LEFT_SHIFT;
			case RIGHT_SHIFT_ASSIGNMENT -> Tree.Kind.RIGHT_SHIFT;
			case UNSIGNED_RIGHT_SHIFT_ASSIGNMENT -> Tree.Kind.UNSIGNED_RIGHT_SHIFT;
			default -> throw Rejection.notYet(compound, "This compound assignment");
		};
		TypeMirror targetType = unit.type(targetTree);
		if (types().isChar(targetType)) {
			// c += 2 works on the char's number and stores a char again
			Expression value = operators.operation(kind, targetTree, charCode(targetTree, target),
					compound.getExpression(), targetType, compound);
			return Statement.assignment(target, call(Expression.field(name("string"), "char"), value));
		}
		Expression value = operators.operation(kind, targetTree, target, compound.getExpression(), targetType,
				compound);
		return Statement.assignment(target, value);
	}

	private Statement ifChain(IfTree first) {
		List<Statement.If.Branch> branches = new ArrayList<>();
		IfTree current = first;
		while (true) {
			branches.add(new Statement.If.Branch(expression(current.getCondition()), body(current.getThenStatement())));
			StatementTree orElse = current.getElseStatement();
			if (orElse instanceof IfTree next) {
				current = next;
			} else {
				return new Statement.If(branches, orElse == null ? null : body(orElse));
			}
		}
	}

	/**
	 * Whether a value needs statements in Lua: a conditional {@code and}/{@code or} cannot express, a switch, or a
	 * stream, which is a loop.
	 */
	private boolean needsStatements(ExpressionTree value) {
		ExpressionTree inner = withoutParentheses(value);
		return (inner instanceof ConditionalExpressionTree conditional && !operators.safeAsAndOr(conditional))
				|| inner instanceof SwitchExpressionTree || streams.pipeline(inner) != null;
	}

	/**
	 * The statements that hand a value to {@code sink}, which assigns or returns it: one statement, or an if chain for
	 * a conditional or a switch that Lua has no expression for, with the sink in every branch.
	 */
	List<Statement> deliver(ExpressionTree value, Function<Expression, Statement> sink) {
		ExpressionTree inner = withoutParentheses(value);
		if (inner instanceof ConditionalExpressionTree conditional && !operators.safeAsAndOr(conditional)) {
			List<Statement.If.Branch> branches = new ArrayList<>();
			branches.add(new Statement.If.Branch(expression(conditional.getCondition()),
					blockOf(deliver(conditional.getTrueExpression(), sink))));
			ExpressionTree otherwise = conditional.getFalseExpression();
			List<Statement> loweredOtherwise = deliver(otherwise, sink);
			// a ? x : b ? y : z reads as if, elseif, else
			if (withoutParentheses(otherwise) instanceof ConditionalExpressionTree
					&& loweredOtherwise.size() == 1 && loweredOtherwise.getFirst() instanceof Statement.If nested) {
				branches.addAll(nested.branches());
				return List.of(new Statement.If(branches, nested.orElse()));
			}
			return List.of(new Statement.If(branches, blockOf(loweredOtherwise)));
		}
		if (inner instanceof SwitchExpressionTree switched) {
			return switches.switchChain(switched, switched.getExpression(), switched.getCases(), sink);
		}
		StreamLoops.Pipeline pipeline = streams.pipeline(inner);
		if (pipeline != null) {
			return streams.lower(pipeline, new StreamLoops.Destination.Sink(sink));
		}
		return List.of(sink.apply(expression(value)));
	}

	/** The statements that return a value: a stream's loop may return from inside, as anyMatch's does. */
	List<Statement> returned(ExpressionTree value) {
		StreamLoops.Pipeline pipeline = streams.pipeline(value);
		if (pipeline != null) {
			return streams.lower(pipeline, new StreamLoops.Destination.Returned());
		}
		return deliver(value, lowered -> new Statement.Return(List.of(lowered)));
	}

	static Block blockOf(List<Statement> statements) {
		return Block.of(statements.toArray(Statement[]::new));
	}

	static ExpressionTree withoutParentheses(ExpressionTree tree) {
		ExpressionTree inner = tree;
		while (inner instanceof ParenthesizedTree parenthesized) {
			inner = parenthesized.getExpression();
		}
		return inner;
	}

	private List<Statement> returnStatement(ReturnTree returned) {
		ExpressionTree value = returned.getExpression();
		if (value == null) {
			return List.of(new Statement.Return(inConstructor ? List.of(SELF) : List.of()));
		}
		if (types().isReturnValues(unit.type(value))) {
			return List.of(new Statement.Return(returnedValues(value)));
		}
		return returned(value);
	}

	/**
	 * The values a {@code @LuaReturnValues} record stands for, in a {@code return}: each argument of
	 * {@code new GroundHeight(h, true)}, the locals of a value read from a call, or a call, whose values pass through.
	 */
	private List<Expression> returnedValues(ExpressionTree value) {
		ExpressionTree inner = withoutParentheses(value);
		if (inner instanceof NewClassTree creation && creation.getClassBody() == null) {
			return arguments(creation.getArguments());
		}
		Map<String, String> values = inner instanceof IdentifierTree local
				? returnValues.get(unit.element(local)) : null;
		if (values != null) {
			return values.values().stream().map(component -> (Expression) name(component)).toList();
		}
		if (inner instanceof MethodInvocationTree) {
			return List.of(expression(inner));
		}
		throw new Rejection(value, "Several Lua values are returned as they are made: return new "
				+ unit.type(value).toString().replaceAll(".*\\.", "") + "(...), or the call that returns them.");
	}

	/** Whether the tree refers to the element anywhere. */
	boolean uses(Tree tree, Element element) {
		return new TreeSearch() {
			@Override
			public Boolean visitIdentifier(IdentifierTree node, Void unused) {
				return facts.refersTo(node, element);
			}
		}.foundIn(tree);
	}

	/** A local the Lua needs and the Java does not have, with a name no other local of this code has. */
	String invent(String preferred, Tree where) {
		String candidate = unit.freshLocal(preferred, where);
		for (int suffix = 2; !invented.add(candidate); suffix++) {
			candidate = unit.freshLocal(preferred + suffix, where);
		}
		return candidate;
	}

	// ---- Expressions

	Expression expression(ExpressionTree tree) {
		Expression lowered = asWritten(tree);
		CharConversions.Conversion conversion = unit.charConversion(tree);
		if (conversion == null) {
			return lowered;
		}
		return switch (conversion) {
			case TO_NUMBER -> charCode(tree, lowered);
			case TO_CHAR -> character(tree, lowered);
		};
	}

	/** The expression without the change between char and number its place may add; see {@link CharConversions}. */
	Expression asWritten(ExpressionTree tree) {
		return switch (tree) {
			case LiteralTree literal -> literal(literal);
			case IdentifierTree identifier -> identifier(identifier);
			case MemberSelectTree select -> memberSelect(select);
			case MethodInvocationTree call -> invocation(call);
			case NewClassTree creation -> objectCreation.lower(creation);
			case BinaryTree operation -> operators.binaryOperation(operation);
			case UnaryTree operation -> operators.unaryOperation(operation);
			case ConditionalExpressionTree conditional -> operators.conditional(conditional);
			case ParenthesizedTree parenthesized -> expression(parenthesized.getExpression());
			case TypeCastTree cast -> operators.cast(cast);
			case ArrayAccessTree access ->
					new Expression.Index(expression(access.getExpression()), operators.index(access.getIndex()));
			case NewArrayTree array -> operators.newArray(array);
			case LambdaExpressionTree lambda -> lambdas.lambda(lambda);
			case MemberReferenceTree reference -> lambdas.memberReference(reference);
			case InstanceOfTree instance -> typeTests.instanceOf(instance);
			case AssignmentTree _, CompoundAssignmentTree _ ->
					throw new Rejection(tree, "Lua has no assignment expressions; put the assignment on its own line.");
			case SwitchExpressionTree switched -> throw new Rejection(switched, "In Lua a switch is a statement; assign"
					+ " the switch to a local, or return it, instead of using it inside an expression.");
			default -> throw Rejection.notYet(tree, "This kind of expression (" + tree.getKind() + ")");
		};
	}

	private Expression literal(LiteralTree literal) {
		Object value = literal.getValue();
		return switch (literal.getKind()) {
			case INT_LITERAL, LONG_LITERAL -> integerLiteral(literal, ((Number) value).longValue());
			case FLOAT_LITERAL, DOUBLE_LITERAL -> floatLiteral(literal, ((Number) value).doubleValue());
			case BOOLEAN_LITERAL -> new Expression.BooleanLiteral((Boolean) value);
			case NULL_LITERAL -> new Expression.Nil();
			case STRING_LITERAL ->
					new Expression.StringLiteral((String) value, unit.sourceText(literal).startsWith("\"\"\""));
			case CHAR_LITERAL -> Expression.string(value.toString());
			default -> throw Rejection.notYet(literal, "This literal");
		};
	}

	/**
	 * A char where Java reads its number: {@code string.byte(c)}. A Lua string holds bytes, so a char is one byte, and
	 * only an ASCII character has the number Java gives it.
	 */
	private Expression charCode(ExpressionTree tree, Expression character) {
		if (withoutParentheses(tree) instanceof LiteralTree literal && literal.getValue() instanceof Character value
				&& value > 127) {
			throw new Rejection(tree, "A Lua string holds bytes, and '" + value + "' takes several, so Lua has no one"
					+ " number for it; compare it as a char, or use its number, " + (int) value + ", as an int.");
		}
		return call(Expression.field(name("string"), "byte"), character);
	}

	/** A number where Java stores a char: {@code string.char(n)}, or the character itself for a literal. */
	private Expression character(ExpressionTree tree, Expression number) {
		if (withoutParentheses(tree) instanceof LiteralTree literal && literal.getValue() instanceof Integer value
				&& value >= 0 && value <= 127) {
			return Expression.string(Character.toString(value));
		}
		if (types().isFloating(unit.type(tree))) {
			unit.use(Helper.TO_INTEGER);
			return call(Expression.field(name("string"), "char"), Helper.TO_INTEGER.invoke(number));
		}
		return call(Expression.field(name("string"), "char"), number);
	}

	/**
	 * An integer literal as written where Lua reads it the same: hexadecimal stays, binary and octal become decimal.
	 */
	private Expression integerLiteral(LiteralTree literal, long value) {
		String text = unit.sourceText(literal).replace("_", "").replaceAll("[lL]$", "");
		if (value >= 0 && (text.startsWith("0x") || text.startsWith("0X"))) {
			return new Expression.NumberLiteral(text);
		}
		return integer(value);
	}

	private Expression floatLiteral(LiteralTree literal, double value) {
		String text = unit.sourceText(literal).replace("_", "").replaceAll("[fFdD]$", "");
		if (text.contains("x") || text.contains("X") || text.startsWith(".") || text.endsWith(".")) {
			// Hexadecimal floats and bare decimal points are valid Java; Double.toString gives Lua a plain numeral
			text = Double.toString(value);
		}
		return new Expression.NumberLiteral(text);
	}

	private Expression identifier(IdentifierTree identifier) {
		String javaName = identifier.getName().toString();
		if (javaName.equals("this")) {
			return self(identifier);
		}
		if (javaName.equals("super")) {
			throw Rejection.notYet(identifier, "Calling a library class's method through super");
		}
		Element element = unit.element(identifier);
		if (returnValues.containsKey(element)) {
			TypeElement record = (TypeElement) ((DeclaredType) unit.type(identifier)).asElement();
			throw new Rejection(identifier, "The values a Lua function returns exist only at its call; read them"
					+ " through " + javaName + "'s components, such as " + javaName + "."
					+ record.getRecordComponents().getFirst().getSimpleName() + "().");
		}
		return switch (element.getKind()) {
			case LOCAL_VARIABLE, PARAMETER, RESOURCE_VARIABLE, EXCEPTION_PARAMETER, BINDING_VARIABLE ->
					aliases.getOrDefault(element, name(unit.codeLocalName(element, identifier)));
			case FIELD -> field((VariableElement) element, null, identifier);
			case ENUM_CONSTANT -> enumConstant((VariableElement) element, identifier);
			default -> throw Rejection.notYet(identifier, "Using " + javaName + " as a value");
		};
	}

	/**
	 * An enum constant: of the plugin's, {@code Mode.FAST}, whose value is the string of its name; of the API,
	 * {@code GameState.GAME}, whose value is the client's.
	 */
	Expression enumConstant(VariableElement constant, Tree where) {
		TypeElement owner = JavaTypes.owner(constant);
		if (types().isApi(owner)) {
			return api.enumConstant(constant, where);
		}
		return Expression.field(unit.moduleReference(owner), types().memberName(constant));
	}

	Expression self(Tree where) {
		if (!hasSelf) {
			if (unit.isInEntry(where)) {
				throw new Rejection(where, "The plugin class becomes main.lua, whose fields and methods are locals with"
						+ " no object to pass around; pass what is needed instead of this.");
			}
			throw new IllegalStateException("this outside an instance context at " + unit.origin(where));
		}
		return SELF;
	}

	/** Whether the receiver is {@code this} or {@code super}, both {@code self} in Lua when a field is read. */
	static boolean isThis(@Nullable ExpressionTree receiver) {
		return receiver instanceof IdentifierTree identifier
				&& (identifier.getName().contentEquals("this") || identifier.getName().contentEquals("super"));
	}

	static boolean isSuper(@Nullable ExpressionTree receiver) {
		return receiver instanceof IdentifierTree identifier && identifier.getName().contentEquals("super");
	}

	/**
	 * The object an unqualified instance member of the class is read on: {@code self} in that class or one that
	 * extends it, and from an inner class the outer object it keeps, {@code self.outer}, as far out as the class is.
	 */
	Expression receiverFor(TypeElement owner, Tree where) {
		Expression receiver = self(where);
		TypeElement current = unit.enclosingClass(where);
		while (!types().isWithin(current.asType(), owner.asType())) {
			if (current.getNestingKind() == NestingKind.LOCAL) {
				throw new Rejection(where, "A class inside a method takes self for its own objects, so its methods"
						+ " cannot read the outer object's members as well; read what they need into a local before"
						+ " the class.");
			}
			if (current.getNestingKind() == NestingKind.ANONYMOUS) {
				if (anonymousObject) {
					throw new Rejection(where, "An anonymous class's methods take self for their own object, so they"
							+ " cannot read the outer object's members as well; read what they need into a local before"
							+ " the class, which its methods can use.");
				}
				// An anonymous class lowered as a function sees the outer self as it is
				current = unit.enclosingClassOf(current);
			} else if (JavaTypes.isInner(current) && current.getEnclosingElement() instanceof TypeElement outer) {
				receiver = Expression.field(receiver, "outer");
				current = outer;
			} else {
				break;
			}
		}
		return receiver;
	}

	/** A field: of the API, a static member of a plugin class, a field of an instance, or a library constant. */
	private Expression field(VariableElement field, @Nullable ExpressionTree receiver, Tree where) {
		TypeElement owner = JavaTypes.owner(field);
		if (types().isApi(owner)) {
			return api.field(field, receiver, where);
		}
		TypeElement settings = types().configType(field.asType());
		if (settings != null && types().isPluginType(owner)) {
			// A field of settings holds nothing: what is read through it is the settings module
			return unit.moduleReference(settings);
		}
		if (types().isPluginType(owner)) {
			unit.checkReachable(owner, where);
			if (field.getModifiers().contains(Modifier.STATIC) || unit.isEntry(owner)) {
				return unit.staticMember(owner, field);
			}
			Expression target = receiver == null ? receiverFor(owner, where)
					: isThis(receiver) ? self(where)
					: expression(receiver);
			return Expression.field(target, types().memberName(field));
		}
		return library.numberMethods().staticField(field, where);
	}

	private Expression memberSelect(MemberSelectTree select) {
		if (select.getIdentifier().contentEquals("length")
				&& unit.type(select.getExpression()).getKind() == TypeKind.ARRAY) {
			return new Expression.Unary(UnaryOperator.LENGTH, expression(select.getExpression()));
		}
		if (select.getIdentifier().contentEquals("this")
				&& unit.element(select.getExpression()) instanceof TypeElement outer) {
			// Outer.this, the outer object an inner one keeps
			return receiverFor(outer, select);
		}
		Element element = unit.element(select);
		if (element.getKind() == ElementKind.FIELD) {
			return field((VariableElement) element, select.getExpression(), select);
		}
		if (element.getKind() == ElementKind.ENUM_CONSTANT) {
			return enumConstant((VariableElement) element, select);
		}
		throw Rejection.notYet(select, "Using " + select + " as a value");
	}

	private Expression invocation(MethodInvocationTree call) {
		ExecutableElement method = (ExecutableElement) unit.element(call);
		TypeElement owner = JavaTypes.owner(method);
		ExpressionTree receiver = call.getMethodSelect() instanceof MemberSelectTree select
				? select.getExpression() : null;
		List<String> entryNames = receiver instanceof IdentifierTree entry ? entries.get(unit.element(entry)) : null;
		if (entryNames != null) {
			return switch (method.getSimpleName().toString()) {
				case "getKey" -> name(entryNames.get(0));
				case "getValue" -> name(entryNames.get(1));
				default -> throw Rejection.notYet(call, "Calling " + method.getSimpleName() + " on a map entry");
			};
		}
		Map<String, String> values = receiver instanceof IdentifierTree local
				? returnValues.get(unit.element(local)) : null;
		if (values != null) {
			// ground.height() is the local that holds the value, as the call that returned it named it
			String component = values.get(method.getSimpleName().toString());
			if (component == null) {
				throw new Rejection(call, "The values a Lua function returns are only read, one by one; call one of "
						+ String.join(", ", values.keySet()) + " instead.");
			}
			return name(component);
		}
		if (receiver != null && types().isThrowable(unit.type(receiver))) {
			return exceptions.caughtErrorMethod(call, method, receiver);
		}
		if (types().isApi(owner)) {
			return api.invocation(call, method, owner, receiver);
		}
		if (types().isPluginType(owner)) {
			return pluginCalls.invocation(call, method, owner, receiver);
		}
		if (unit.elements.isFunctionalInterface(owner) && method.getModifiers().contains(Modifier.ABSTRACT)
				&& receiver != null) {
			return new Expression.Call(expression(receiver), arguments(call.getArguments()));
		}
		return library.invocation(call, method, receiver);
	}

	/**
	 * The arguments of a call of the plugin's method: an array passed for its varargs is spread with
	 * {@code table.unpack}, as Java spreads it over the parameters.
	 */
	List<Expression> arguments(ExecutableElement method, List<? extends ExpressionTree> arguments) {
		if (method.isVarArgs() && arguments.size() == method.getParameters().size()
				&& unit.type(arguments.getLast()).getKind() == TypeKind.ARRAY) {
			List<Expression> lowered = new ArrayList<>(arguments(arguments.subList(0, arguments.size() - 1)));
			lowered.add(call(Expression.field(name("table"), "unpack"), expression(arguments.getLast())));
			return lowered;
		}
		return arguments(arguments);
	}

	List<Expression> arguments(List<? extends ExpressionTree> arguments) {
		return arguments.stream().map(this::expression).toList();
	}

	/**
	 * Takes over what the code around a lambda or anonymous class knows about its locals, which the inner code sees
	 * as upvalues.
	 */
	void inherit(BodyLowering outer) {
		nonNegative.addAll(outer.nonNegative);
		entries.putAll(outer.entries);
		returnValues.putAll(outer.returnValues);
		caughtErrors.addAll(outer.caughtErrors);
		invented.addAll(outer.invented);
		aliases.putAll(outer.aliases);
	}
}
