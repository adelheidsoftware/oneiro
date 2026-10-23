package org.adelheid.oneiro.java;

import static org.adelheid.oneiro.lua.Expression.binary;
import static org.adelheid.oneiro.lua.Expression.call;
import static org.adelheid.oneiro.lua.Expression.integer;
import static org.adelheid.oneiro.lua.Expression.name;

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssertTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BindingPatternTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.CaseLabelTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ConstantCaseLabelTree;
import com.sun.source.tree.ContinueTree;
import com.sun.source.tree.DefaultCaseLabelTree;
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
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.PatternCaseLabelTree;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import org.adelheid.oneiro.lua.BinaryOperator;
import org.adelheid.oneiro.lua.Block;
import org.adelheid.oneiro.lua.Comment;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.FunctionBody;
import org.adelheid.oneiro.lua.NameUses;
import org.adelheid.oneiro.lua.SourcePosition;
import org.adelheid.oneiro.lua.Statement;
import org.adelheid.oneiro.lua.Trivia;
import org.adelheid.oneiro.lua.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * Lowers the statements and expressions of one method, constructor or initializer into the Lua tree, as
 * docs/java-subset.md describes. Library calls and string building have classes of their own; this class keeps the
 * scope they share: whether {@code this} exists, the loops a {@code continue} jumps within, which integers are known
 * to be non-negative (so that {@code /} can be Lua's {@code //}), and where the value of a switch expression goes.
 */
final class BodyLowering {

	private static final Expression SELF = name("self");
	/** The compound assignments that keep a non-negative value non-negative when given one. */
	private static final Set<Tree.Kind> RAISING = Set.of(Tree.Kind.PLUS_ASSIGNMENT, Tree.Kind.MULTIPLY_ASSIGNMENT,
			Tree.Kind.DIVIDE_ASSIGNMENT, Tree.Kind.REMAINDER_ASSIGNMENT);

	final UnitLowering unit;
	private final boolean hasSelf;
	private final boolean inConstructor;
	// Whether self is an anonymous class's object, whose methods cannot read the outer object's members
	private boolean anonymousObject;
	private final LibraryCalls library;
	private final ApiCalls api;
	private final StringBuilding strings;
	private final StreamLoops streams;
	private final Comparators comparators;
	private final MethodReferences methodReferences;
	private final Deque<Loop> loops = new ArrayDeque<>();
	private final Set<Element> nonNegative = new HashSet<>();
	// Where a yield hands its value, for each switch expression being lowered, innermost first
	private final Deque<Function<Expression, Statement>> sinks = new ArrayDeque<>();
	// Map.Entry loop variables and the Lua names of their key and value, for for (var e : map.entrySet())
	private final Map<Element, List<String>> entries = new HashMap<>();
	// Locals holding a Lua function's several return values, and the Lua local of each record component by accessor
	private final Map<Element, Map<String, String>> returnValues = new HashMap<>();
	// The parameters of the catch clauses being lowered, which stand for the error value pcall hands back
	private final Set<Element> caughtErrors = new HashSet<>();
	// The Lua locals this code introduces that the Java does not have, so that two of them never share a name
	private final Set<String> invented = new HashSet<>();
	// Variables that stand for a value of the code around them: a stream lambda's parameter for the loop's element, a
	// pattern's variable for the value it tests
	private final Map<Element, Expression> aliases = new HashMap<>();

	/** A loop being lowered, and the label its {@code continue} statements jump to, or null when it has none. */
	private record Loop(@Nullable String continueLabel) {
	}

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
			List<Statement> parts;
			try {
				parts = statement(statement);
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
				boolean first = index == 0;
				boolean last = index == parts.size() - 1;
				Trivia trivia = new Trivia(
						first && blankLine,
						first ? List.copyOf(pending) : List.of(),
						last && trailing != null ? String.join(" ", trailing.lines()) : null);
				lowered.add(new Block.Entry(parts.get(index), trivia, origin));
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

	List<Statement> statement(StatementTree statement) {
		return switch (statement) {
			case VariableTree variable -> localVariable(variable);
			case ExpressionStatementTree expression -> expressionStatement(expression.getExpression());
			case IfTree branch -> List.of(ifChain(branch));
			case WhileLoopTree loop ->
					List.of(new Statement.While(expression(loop.getCondition()), loopBody(loop.getStatement(), List.of())));
			case DoWhileLoopTree loop -> List.of(new Statement.Repeat(
					loopBody(loop.getStatement(), List.of()),
					new Expression.Unary(UnaryOperator.NOT, expression(loop.getCondition()))));
			case ForLoopTree loop -> forLoop(loop);
			case EnhancedForLoopTree loop -> List.of(forEach(loop));
			case BreakTree jump -> {
				if (jump.getLabel() != null) {
					throw labelledJump(jump);
				}
				yield List.of(new Statement.Break());
			}
			case ContinueTree jump -> List.of(continueStatement(jump));
			case ReturnTree returned -> returnStatement(returned);
			case ThrowTree thrown -> List.of(throwStatement(thrown));
			case BlockTree nested -> List.of(new Statement.Do(block(nested)));
			case EmptyStatementTree _ -> List.of();
			case SwitchTree switched -> switchChain(switched, switched.getExpression(), switched.getCases(), null);
			case YieldTree yielded -> {
				Function<Expression, Statement> sink = sinks.peek();
				if (sink == null) {
					throw new IllegalStateException("yield outside a switch expression at " + unit.origin(yielded));
				}
				yield deliver(yielded.getValue(), sink);
			}
			case TryTree attempt -> tryStatement(attempt);
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

	private static Rejection labelledJump(Tree tree) {
		return new Rejection(tree, "Lua's break and continue only reach the innermost loop; move the loops into a"
				+ " method and return from it.");
	}

	private List<Statement> localVariable(VariableTree variable) {
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
			lowered.addAll(deliver(initializer, value -> assignment(name(luaName), value)));
			return lowered;
		}
		// A local that starts non-negative and is only raised, in the block it lives in, stays non-negative
		Element element = unit.element(variable);
		if (knownNonNegative(initializer) && (variable.getModifiers().getFlags().contains(Modifier.FINAL)
				|| onlyRaises(unit.path(variable).getParentPath().getLeaf(), element))) {
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
		if (!(BodyLowering.withoutParentheses(initializer) instanceof MethodInvocationTree)) {
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
						&& select.getExpression() instanceof IdentifierTree receiver && refersTo(receiver, element)) {
					read.add(select.getIdentifier().toString());
				}
				return super.visitMethodInvocation(node, null);
			}

			@Override
			public Void visitReturn(ReturnTree node, Void unused) {
				ExpressionTree value = node.getExpression();
				if (value != null && refersTo(withoutParentheses(value), element)) {
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
	private void alias(VariableTree variable, Expression value) {
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
		return unit.names.luaName(javaName);
	}

	/** An expression used as a statement; usually one Lua statement, but each chained {@code append} is its own. */
	private List<Statement> expressionStatement(ExpressionTree expression) {
		return switch (expression) {
			case AssignmentTree assignment -> {
				Expression target = assignable(assignment.getVariable());
				yield deliver(assignment.getExpression(), value -> assignment(target, value));
			}
			case CompoundAssignmentTree compound -> List.of(compoundAssignment(compound));
			case UnaryTree unary when isStep(unary.getKind()) -> {
				Expression target = assignable(unary.getExpression());
				BinaryOperator operator = unary.getKind() == Tree.Kind.POSTFIX_INCREMENT
						|| unary.getKind() == Tree.Kind.PREFIX_INCREMENT ? BinaryOperator.ADD : BinaryOperator.SUBTRACT;
				if (types().isChar(unit.type(unary.getExpression()))) {
					// c++ steps the char's number and stores a char again
					Expression stepped = binary(charCode(unary.getExpression(), target), operator, integer(1));
					yield List.of(new Statement.Assignment(List.of(target),
							List.of(call(Expression.field(name("string"), "char"), stepped))));
				}
				yield List.of(new Statement.Assignment(List.of(target), List.of(binary(target, operator, integer(1)))));
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

	private static boolean isStep(Tree.Kind kind) {
		return kind == Tree.Kind.POSTFIX_INCREMENT || kind == Tree.Kind.PREFIX_INCREMENT
				|| kind == Tree.Kind.POSTFIX_DECREMENT || kind == Tree.Kind.PREFIX_DECREMENT;
	}

	/** The Lua target of an assignment: a local, a field, or an array element. */
	private Expression assignable(ExpressionTree target) {
		Element assigned = unit.trees.getElement(unit.path(target));
		if (assigned != null && assigned.getKind() == ElementKind.FIELD && types().configType(assigned.asType()) != null) {
			throw new Rejection(target, "A field of settings needs no value: every call through it calls the settings"
					+ " module, so remove the assignment.");
		}
		checkNotIntoCopy(target);
		return switch (target) {
			case IdentifierTree identifier -> identifier(identifier);
			case MemberSelectTree select -> memberSelect(select);
			case ArrayAccessTree access -> new Expression.Index(expression(access.getExpression()), index(access.getIndex()));
			case ParenthesizedTree parenthesized -> assignable(parenthesized.getExpression());
			default -> throw Rejection.notYet(target, "Assigning to this kind of expression (" + target.getKind() + ")");
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
		Element field = holder instanceof MemberSelectTree || holder instanceof IdentifierTree ? unit.element(holder) : null;
		if (field != null && field.getKind() == ElementKind.FIELD && JavaTypes.hasAnnotation(field, JavaTypes.LUA_COPY)) {
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
		if (containsCall(targetTree)) {
			throw new Rejection(targetTree, "Lua would evaluate this target twice; store the part with the call in a"
					+ " local first.");
		}
		Expression target = assignable(targetTree);
		if (compound.getKind() == Tree.Kind.PLUS_ASSIGNMENT && types().isString(unit.type(targetTree))) {
			Expression appended = strings.concatOperand(compound.getExpression());
			return new Statement.Assignment(List.of(target), List.of(binary(target, BinaryOperator.CONCAT, appended)));
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
			Expression value = operation(kind, targetTree, charCode(targetTree, target), compound.getExpression(),
					targetType, compound);
			return new Statement.Assignment(List.of(target),
					List.of(call(Expression.field(name("string"), "char"), value)));
		}
		Expression value = operation(kind, targetTree, target, compound.getExpression(), targetType, compound);
		return new Statement.Assignment(List.of(target), List.of(value));
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

	private static Statement assignment(Expression target, Expression value) {
		return new Statement.Assignment(List.of(target), List.of(value));
	}

	/**
	 * Whether a value needs statements in Lua: a conditional {@code and}/{@code or} cannot express, a switch, or a
	 * stream, which is a loop.
	 */
	private boolean needsStatements(ExpressionTree value) {
		ExpressionTree inner = withoutParentheses(value);
		return (inner instanceof ConditionalExpressionTree conditional && !safeAsAndOr(conditional))
				|| inner instanceof SwitchExpressionTree || streams.pipeline(inner) != null;
	}

	/**
	 * The statements that hand a value to {@code sink}, which assigns or returns it: one statement, or an if chain for
	 * a conditional or a switch that Lua has no expression for, with the sink in every branch.
	 */
	List<Statement> deliver(ExpressionTree value, Function<Expression, Statement> sink) {
		ExpressionTree inner = withoutParentheses(value);
		if (inner instanceof ConditionalExpressionTree conditional && !safeAsAndOr(conditional)) {
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
			return switchChain(switched, switched.getExpression(), switched.getCases(), sink);
		}
		StreamLoops.Pipeline pipeline = streams.pipeline(inner);
		if (pipeline != null) {
			return streams.lower(pipeline, new StreamLoops.Destination.Sink(sink));
		}
		return List.of(sink.apply(expression(value)));
	}

	/** The statements that return a value: a stream's loop may return from inside, as anyMatch's does. */
	private List<Statement> returned(ExpressionTree value) {
		StreamLoops.Pipeline pipeline = streams.pipeline(value);
		if (pipeline != null) {
			return streams.lower(pipeline, new StreamLoops.Destination.Returned());
		}
		return deliver(value, lowered -> new Statement.Return(List.of(lowered)));
	}

	private static Block blockOf(List<Statement> statements) {
		return Block.of(statements.toArray(Statement[]::new));
	}

	static ExpressionTree withoutParentheses(ExpressionTree tree) {
		ExpressionTree inner = tree;
		while (inner instanceof ParenthesizedTree parenthesized) {
			inner = parenthesized.getExpression();
		}
		return inner;
	}

	// ---- Switch

	/**
	 * A switch as an if/elseif chain, with {@code default} as the else. The selector goes into a local when it has a
	 * call, which Java evaluates once, or when its Lua is more than a name or field and more than one case compares
	 * it; otherwise each case compares it as it is. With a sink, the switch is an expression, and each case hands its
	 * value to the sink.
	 */
	private List<Statement> switchChain(Tree switchTree, ExpressionTree selectorTree, List<? extends CaseTree> cases,
			@Nullable Function<Expression, Statement> sink) {
		List<Statement> lowered = new ArrayList<>();
		Expression selector = expression(selectorTree);
		long comparisons = cases.stream()
				.flatMap(arm -> arm.getLabels().stream())
				.filter(label -> label instanceof ConstantCaseLabelTree || label instanceof PatternCaseLabelTree)
				.count();
		if (containsCall(selectorTree) || (!isPlain(selector) && comparisons > 1)) {
			String local = unit.freshLocal(selectorName(selectorTree), switchTree);
			lowered.add(new Statement.Local(List.of(local), List.of(selector)));
			selector = name(local);
		}
		List<Statement.If.Branch> branches = new ArrayList<>();
		Block orElse = null;
		List<Expression> tests = new ArrayList<>();
		boolean isDefault = false;
		int cursor = unit.end(selectorTree);
		TypeMirror selectorType = unit.type(selectorTree);
		for (int index = 0; index < cases.size(); index++) {
			CaseTree arm = cases.get(index);
			boolean last = index == cases.size() - 1;
			// A pattern that every value matches, case Object o, tests nothing
			boolean unconditional = false;
			for (CaseLabelTree label : arm.getLabels()) {
				switch (label) {
					case ConstantCaseLabelTree constant -> tests.add(binary(selector, BinaryOperator.EQUAL,
							expression(constant.getConstantExpression())));
					case DefaultCaseLabelTree _ -> isDefault = true;
					case PatternCaseLabelTree pattern -> {
						VariableTree variable = patternVariable(pattern.getPattern());
						declare(variable);
						alias(variable, selector);
						TypeMirror type = unit.type(variable);
						if (types().isWithin(selectorType, type)) {
							unconditional = true;
						} else {
							tests.add(typeTest(selector, type, label));
						}
					}
					default -> throw Rejection.notYet(label, "This kind of case label");
				}
			}
			ExpressionTree guard = arm.getGuard();
			Expression condition = tests.stream().reduce((a, b) -> binary(a, BinaryOperator.OR, b)).orElse(null);
			if (guard != null) {
				condition = condition == null
						? expression(guard)
						: binary(condition, BinaryOperator.AND, expression(guard));
			} else if (unconditional) {
				isDefault = true;
			}
			// case A: case B: with nothing between them share the statements that follow
			if (arm.getCaseKind() == CaseTree.CaseKind.STATEMENT && arm.getStatements().isEmpty() && !last) {
				continue;
			}
			Block body = armBody(arm, cursor, last ? unit.end(switchTree) - 1 : unit.end(arm), sink, last);
			cursor = unit.end(arm);
			if (isDefault) {
				orElse = body;
			} else {
				if (condition == null) {
					throw new IllegalStateException("a case that tests nothing");
				}
				branches.add(new Statement.If.Branch(condition, body));
			}
			tests.clear();
			isDefault = false;
		}
		Block otherwise = orElse == null || orElse.isEmpty() ? null : orElse;
		if (!branches.isEmpty()) {
			lowered.add(new Statement.If(branches, otherwise));
		} else if (otherwise != null) {
			lowered.add(new Statement.Do(otherwise));
		}
		return lowered;
	}

	/** Whether Lua reads the value as cheaply as a local: a name, a literal, or fields and indexes of those. */
	private static boolean isPlain(Expression value) {
		return switch (value) {
			case Expression.Name _, Expression.NumberLiteral _, Expression.StringLiteral _, Expression.BooleanLiteral _,
					Expression.Nil _ -> true;
			case Expression.Field field -> isPlain(field.target());
			case Expression.Index index -> isPlain(index.target()) && isPlain(index.key());
			default -> false;
		};
	}

	/** {@code kind} for {@code event.getKind()} or {@code event.kind()}, the name for a selector read into a local. */
	private static String selectorName(ExpressionTree selector) {
		if (withoutParentheses(selector) instanceof MethodInvocationTree call && call.getArguments().isEmpty()) {
			String method = switch (call.getMethodSelect()) {
				case MemberSelectTree select -> select.getIdentifier().toString();
				case IdentifierTree identifier -> identifier.getName().toString();
				default -> "";
			};
			if (method.length() > 3 && method.startsWith("get") && Character.isUpperCase(method.charAt(3))) {
				return Character.toLowerCase(method.charAt(3)) + method.substring(4);
			}
			if (!method.isEmpty()) {
				return method;
			}
		}
		return "selected";
	}

	/**
	 * One case as the body of an if branch, with the comments from the end of the previous case on, so that a comment
	 * above {@code case} opens its branch.
	 */
	private Block armBody(CaseTree arm, int from, int to, @Nullable Function<Expression, Statement> sink,
			boolean last) {
		if (arm.getCaseKind() == CaseTree.CaseKind.STATEMENT) {
			List<? extends StatementTree> statements = arm.getStatements();
			if (!last && canCompleteNormally(statements)) {
				throw new Rejection(arm, "Lua has no fall-through; repeat the shared code or move it into a method.");
			}
			return caseStatements(statements, arm, from, to, sink);
		}
		Tree body = arm.getBody();
		return switch (body) {
			case BlockTree block -> caseStatements(block.getStatements(), arm, from, to, sink);
			case StatementTree statement -> caseStatements(List.of(statement), arm, from, to, sink);
			case ExpressionTree value -> {
				// The value of a switch expression's case, case A -> 1
				List<Comment> leading = comments(unit.comments.take(from, unit.start(value)));
				List<Statement> statements = sink != null ? deliver(value, sink) : expressionStatement(value);
				CommentIndex.SourceComment trailing = unit.comments.takeTrailing(unit.end(value));
				SourcePosition origin = unit.origin(value);
				List<Block.Entry> entries = new ArrayList<>();
				for (int index = 0; index < statements.size(); index++) {
					boolean lastStatement = index == statements.size() - 1;
					entries.add(new Block.Entry(statements.get(index), new Trivia(false,
							index == 0 ? leading : List.of(),
							lastStatement && trailing != null ? String.join(" ", trailing.lines()) : null), origin));
				}
				int closingFrom = trailing != null ? trailing.end() : unit.end(value);
				yield new Block(entries, comments(unit.comments.take(closingFrom, to)));
			}
			default -> throw new IllegalStateException("a case body of kind " + body.getKind());
		};
	}

	/**
	 * A case's statements without the break that ends the case, and without the braces of a case written as a block,
	 * because each branch of the if is a scope of its own.
	 */
	private Block caseStatements(List<? extends StatementTree> statements, CaseTree arm, int from, int to,
			@Nullable Function<Expression, Statement> sink) {
		List<? extends StatementTree> kept = withoutFinalBreak(statements);
		if (kept.size() == 1 && kept.getFirst() instanceof BlockTree block) {
			kept = withoutFinalBreak(block.getStatements());
		}
		checkNoEarlyBreak(kept);
		if (sink != null) {
			checkYieldsAtEnd(kept);
			sinks.push(sink);
		}
		try {
			Block lowered = statements(kept, from, to);
			CommentIndex.SourceComment afterBreak = unit.comments.takeTrailing(unit.end(arm));
			if (afterBreak == null) {
				return lowered;
			}
			List<Comment> closing = new ArrayList<>(lowered.closingComments());
			closing.addAll(comments(List.of(afterBreak)));
			return new Block(lowered.entries(), closing);
		} finally {
			if (sink != null) {
				sinks.pop();
			}
		}
	}

	private static List<? extends StatementTree> withoutFinalBreak(List<? extends StatementTree> statements) {
		if (!statements.isEmpty() && statements.getLast() instanceof BreakTree jump && jump.getLabel() == null) {
			return statements.subList(0, statements.size() - 1);
		}
		return statements;
	}

	/** Whether control can reach the end of the statements, which in a case would fall through into the next one. */
	private static boolean canCompleteNormally(List<? extends StatementTree> statements) {
		if (statements.isEmpty()) {
			return true;
		}
		return switch (statements.getLast()) {
			case BreakTree _, ReturnTree _, ThrowTree _, ContinueTree _, YieldTree _ -> false;
			case BlockTree block -> canCompleteNormally(block.getStatements());
			case IfTree branch -> {
				StatementTree orElse = branch.getElseStatement();
				yield orElse == null || canCompleteNormally(List.of(branch.getThenStatement()))
						|| canCompleteNormally(List.of(orElse));
			}
			default -> true;
		};
	}

	/** A break before the end of a case has no Lua form: Lua cannot leave an if branch early. */
	private static void checkNoEarlyBreak(List<? extends StatementTree> statements) {
		List<BreakTree> found = new ArrayList<>();
		new TreeScanner<Void, Void>() {
			@Override
			public Void visitBreak(BreakTree node, Void unused) {
				if (node.getLabel() == null) {
					found.add(node);
				}
				return null;
			}

			// A break inside these leaves them, not the switch
			@Override
			public Void visitWhileLoop(WhileLoopTree node, Void unused) {
				return null;
			}

			@Override
			public Void visitDoWhileLoop(DoWhileLoopTree node, Void unused) {
				return null;
			}

			@Override
			public Void visitForLoop(ForLoopTree node, Void unused) {
				return null;
			}

			@Override
			public Void visitEnhancedForLoop(EnhancedForLoopTree node, Void unused) {
				return null;
			}

			@Override
			public Void visitSwitch(SwitchTree node, Void unused) {
				return null;
			}

			@Override
			public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
				return null;
			}

			@Override
			public Void visitClass(ClassTree node, Void unused) {
				return null;
			}
		}.scan(statements, null);
		if (!found.isEmpty()) {
			throw new Rejection(found.getFirst(), "Lua has no switch to leave early, and a break there would leave the"
					+ " enclosing loop instead; use if/else inside the case.");
		}
	}

	/**
	 * Each yield must end a path through its case, as the last statement or the last of an if's branches, because
	 * the Lua that assigns the value cannot skip the statements after it.
	 */
	private static void checkYieldsAtEnd(List<? extends StatementTree> statements) {
		Set<YieldTree> atEnd = yieldsAtEnd(statements);
		List<YieldTree> found = new ArrayList<>();
		new TreeScanner<Void, Void>() {
			@Override
			public Void visitYield(YieldTree node, Void unused) {
				found.add(node);
				return super.visitYield(node, null);
			}

			// Their yields belong to them
			@Override
			public Void visitSwitchExpression(SwitchExpressionTree node, Void unused) {
				return null;
			}

			@Override
			public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
				return null;
			}

			@Override
			public Void visitClass(ClassTree node, Void unused) {
				return null;
			}
		}.scan(statements, null);
		for (YieldTree yielded : found) {
			if (!atEnd.contains(yielded)) {
				throw new Rejection(yielded, "In Lua each case's value is assigned or returned at its end, which a yield"
						+ " in the middle cannot reach; move the yield to the end of its case, or of each branch of an if"
						+ " there.");
			}
		}
	}

	private static Set<YieldTree> yieldsAtEnd(List<? extends StatementTree> statements) {
		Set<YieldTree> atEnd = new HashSet<>();
		if (statements.isEmpty()) {
			return atEnd;
		}
		switch (statements.getLast()) {
			case YieldTree yielded -> atEnd.add(yielded);
			case BlockTree block -> atEnd.addAll(yieldsAtEnd(block.getStatements()));
			case IfTree branch -> {
				atEnd.addAll(yieldsAtEnd(List.of(branch.getThenStatement())));
				StatementTree orElse = branch.getElseStatement();
				if (orElse != null) {
					atEnd.addAll(yieldsAtEnd(List.of(orElse)));
				}
			}
			default -> {
			}
		}
		return atEnd;
	}

	// ---- Loops

	/**
	 * A loop body, with the label its {@code continue} statements jump to as its last statement. When statements must
	 * follow the label (a {@code for} loop's update, run after a {@code continue} too), the body goes into a
	 * {@code do ... end} block first, because Lua's goto may not jump into the scope of the body's locals.
	 */
	private Block loopBody(StatementTree body, List<Statement> update) {
		String label = usesContinue(body) ? continueLabel() : null;
		loops.push(new Loop(label));
		Block lowered;
		try {
			lowered = body(body);
		} finally {
			loops.pop();
		}
		if (label == null && update.isEmpty()) {
			return lowered;
		}
		List<Block.Entry> entries = new ArrayList<>();
		if (label != null && !update.isEmpty()) {
			entries.add(Block.Entry.of(new Statement.Do(lowered)));
		} else {
			entries.addAll(lowered.entries());
		}
		if (label != null) {
			entries.add(Block.Entry.of(new Statement.Label(label)));
		}
		update.forEach(statement -> entries.add(Block.Entry.of(statement)));
		List<Comment> closing = label != null && !update.isEmpty() ? List.of() : lowered.closingComments();
		return new Block(entries, closing);
	}

	/**
	 * The label for a loop's {@code continue}: {@code continue}, or {@code continue2} and up inside a loop that already
	 * has one, because Lua forbids a label where one of the same name is visible.
	 */
	private String continueLabel() {
		long enclosing = loops.stream().filter(loop -> loop.continueLabel() != null).count();
		return enclosing == 0 ? "continue" : "continue" + (enclosing + 1);
	}

	/** Whether the body has a {@code continue} for this loop, not counting nested loops, lambdas and classes. */
	private static boolean usesContinue(StatementTree body) {
		Boolean found = new TreeScanner<Boolean, Void>() {
			@Override
			public Boolean visitContinue(ContinueTree node, Void unused) {
				return node.getLabel() == null;
			}

			@Override
			public Boolean visitWhileLoop(WhileLoopTree node, Void unused) {
				return false;
			}

			@Override
			public Boolean visitDoWhileLoop(DoWhileLoopTree node, Void unused) {
				return false;
			}

			@Override
			public Boolean visitForLoop(ForLoopTree node, Void unused) {
				return false;
			}

			@Override
			public Boolean visitEnhancedForLoop(EnhancedForLoopTree node, Void unused) {
				return false;
			}

			@Override
			public Boolean visitLambdaExpression(LambdaExpressionTree node, Void unused) {
				return false;
			}

			@Override
			public Boolean visitClass(ClassTree node, Void unused) {
				return false;
			}

			@Override
			public Boolean reduce(Boolean first, Boolean second) {
				return Boolean.TRUE.equals(first) || Boolean.TRUE.equals(second);
			}
		}.scan(body, null);
		return Boolean.TRUE.equals(found);
	}

	private Statement continueStatement(ContinueTree jump) {
		if (jump.getLabel() != null) {
			throw labelledJump(jump);
		}
		Loop loop = loops.peek();
		if (loop == null || loop.continueLabel() == null) {
			throw new IllegalStateException("continue outside a loop that was scanned for it");
		}
		return new Statement.Goto(loop.continueLabel());
	}

	private List<Statement> forLoop(ForLoopTree loop) {
		Statement numeric = numericFor(loop);
		if (numeric != null) {
			return List.of(numeric);
		}
		List<Statement> lowered = new ArrayList<>();
		for (StatementTree initializer : loop.getInitializer()) {
			lowered.addAll(statement(initializer));
		}
		Expression condition = loop.getCondition() == null
				? new Expression.BooleanLiteral(true)
				: expression(loop.getCondition());
		List<Statement> update = loop.getUpdate().stream()
				.flatMap(step -> expressionStatement(step.getExpression()).stream())
				.toList();
		lowered.add(new Statement.While(condition, loopBody(loop.getStatement(), update)));
		return lowered;
	}

	/**
	 * A loop that can be a Lua numeric for: one integer variable starting at {@code start}, compared with a bound that
	 * the body cannot change, and stepped by a constant the body does not change either.
	 */
	private record NumericShape(VariableTree variable, Element element, ExpressionTree start, Tree.Kind comparison,
			ExpressionTree bound, long step) {
	}

	/** The loop's numeric shape, or null when it has another shape and becomes a while loop. */
	private @Nullable NumericShape numericShape(ForLoopTree loop) {
		if (loop.getInitializer().size() != 1
				|| !(loop.getInitializer().getFirst() instanceof VariableTree variable)
				|| !types().isIntegral(unit.type(variable))) {
			return null;
		}
		ExpressionTree start = variable.getInitializer();
		Element element = unit.element(variable);
		if (start == null || !(loop.getCondition() instanceof BinaryTree condition)
				|| !refersTo(condition.getLeftOperand(), element) || loop.getUpdate().size() != 1) {
			return null;
		}
		Long step = step(loop.getUpdate().getFirst().getExpression(), element);
		if (step == null) {
			return null;
		}
		Tree.Kind comparison = condition.getKind();
		boolean shapeFits = step > 0
				? comparison == Tree.Kind.LESS_THAN || comparison == Tree.Kind.LESS_THAN_EQUAL
				: comparison == Tree.Kind.GREATER_THAN || comparison == Tree.Kind.GREATER_THAN_EQUAL;
		ExpressionTree bound = condition.getRightOperand();
		if (!shapeFits || assigns(loop.getStatement(), element) || !unchangedBy(bound, loop.getStatement())) {
			return null;
		}
		return new NumericShape(variable, element, start, comparison, bound, step);
	}

	/** Whether a loop counts up by 1 from 0 while below a bound: the loops that may count from 1 in Lua instead. */
	boolean countsFromZero(ForLoopTree loop) {
		NumericShape shape = numericShape(loop);
		return shape != null && shape.comparison() == Tree.Kind.LESS_THAN && shape.step() == 1
				&& shape.start() instanceof LiteralTree zero && Integer.valueOf(0).equals(zero.getValue());
	}

	/**
	 * A Lua numeric for, when the loop has that shape. {@code i < n} becomes {@code for i = a, n - 1}. A loop from 0
	 * that {@link OneBasedLoops} picks counts from 1 instead: {@code for i = 1, #list} with {@code list[i]}. Null when
	 * the loop has another shape and becomes a while loop.
	 */
	private @Nullable Statement numericFor(ForLoopTree loop) {
		NumericShape shape = numericShape(loop);
		if (shape == null) {
			return null;
		}
		String luaName = declare(shape.variable());
		Element element = shape.element();
		if (unit.countsFromOne(element)) {
			nonNegative.add(element);
			return new Statement.NumericFor(luaName, integer(1), expression(shape.bound()), null,
					loopBody(loop.getStatement(), List.of()));
		}
		Expression limit = switch (shape.comparison()) {
			case LESS_THAN -> minusOne(expression(shape.bound()));
			case GREATER_THAN -> plusOne(expression(shape.bound()));
			default -> expression(shape.bound());
		};
		if (shape.step() > 0 && knownNonNegative(shape.start())) {
			nonNegative.add(element);
		}
		Expression stepExpression = shape.step() == 1 ? null : integer(shape.step());
		return new Statement.NumericFor(luaName, expression(shape.start()), limit, stepExpression,
				loopBody(loop.getStatement(), List.of()));
	}

	/** The step of {@code i++}, {@code i--}, {@code i += k} or {@code i -= k} for a literal k; null otherwise. */
	private @Nullable Long step(ExpressionTree update, Element variable) {
		if (update instanceof UnaryTree unary && refersTo(unary.getExpression(), variable)) {
			return switch (unary.getKind()) {
				case POSTFIX_INCREMENT, PREFIX_INCREMENT -> 1L;
				case POSTFIX_DECREMENT, PREFIX_DECREMENT -> -1L;
				default -> null;
			};
		}
		if (update instanceof CompoundAssignmentTree compound && refersTo(compound.getVariable(), variable)
				&& compound.getExpression() instanceof LiteralTree literal
				&& literal.getValue() instanceof Number amount && amount.longValue() > 0) {
			return switch (compound.getKind()) {
				case PLUS_ASSIGNMENT -> amount.longValue();
				case MINUS_ASSIGNMENT -> -amount.longValue();
				default -> null;
			};
		}
		return null;
	}

	private Statement forEach(EnhancedForLoopTree loop) {
		ExpressionTree iterable = loop.getExpression();
		TypeMirror type = unit.type(iterable);
		TypeMirror variableType = unit.type(loop.getVariable());
		if (!types().isChar(variableType) && (types().isIntegral(variableType) || types().isFloating(variableType))
				&& ((type instanceof ArrayType array && types().isChar(array.getComponentType()))
						|| (types().isList(type) && types().isChar(types().typeArgument(type, 0))))) {
			throw new Rejection(loop.getVariable(), "Each char would reach this loop as a one-character string, not as"
					+ " its number; loop with a char variable and convert it with (int) where the number is needed.");
		}
		// for (char c : text.toCharArray()) walks the text itself, one character at a time
		if (withoutParentheses(iterable) instanceof MethodInvocationTree call && call.getArguments().isEmpty()
				&& call.getMethodSelect() instanceof MemberSelectTree select
				&& select.getIdentifier().contentEquals("toCharArray")
				&& types().isString(unit.type(select.getExpression()))) {
			String item = declare(loop.getVariable());
			Expression characters = Expression.methodCall(expression(select.getExpression()), "gmatch",
					Expression.string("."));
			return new Statement.GenericFor(List.of(item), List.of(characters), loopBody(loop.getStatement(), List.of()));
		}
		if (type.getKind() == TypeKind.ARRAY || types().isList(type) || types().isDeque(type)) {
			String item = declare(loop.getVariable());
			return new Statement.GenericFor(List.of("_", item), List.of(call(name("ipairs"), expression(iterable))),
					loopBody(loop.getStatement(), List.of()));
		}
		if (types().isSet(type) && !(iterable instanceof MethodInvocationTree)) {
			String item = declare(loop.getVariable());
			return new Statement.GenericFor(List.of(item), List.of(call(name("pairs"), expression(iterable))),
					loopBody(loop.getStatement(), List.of()));
		}
		if (iterable instanceof MethodInvocationTree view && view.getArguments().isEmpty()
				&& view.getMethodSelect() instanceof MemberSelectTree select
				&& types().isMap(unit.type(select.getExpression()))) {
			Expression map = call(name("pairs"), expression(select.getExpression()));
			String method = select.getIdentifier().toString();
			switch (method) {
				case "entrySet" -> {
					String key = unit.names.luaName("key");
					String value = unit.names.luaName("value");
					entries.put(unit.element(loop.getVariable()), List.of(key, value));
					return new Statement.GenericFor(List.of(key, value), List.of(map),
							loopBody(loop.getStatement(), List.of()));
				}
				case "keySet" -> {
					return new Statement.GenericFor(List.of(declare(loop.getVariable())), List.of(map),
							loopBody(loop.getStatement(), List.of()));
				}
				case "values" -> {
					return new Statement.GenericFor(List.of("_", declare(loop.getVariable())), List.of(map),
							loopBody(loop.getStatement(), List.of()));
				}
				default -> {
				}
			}
		}
		throw Rejection.notYet(iterable, "A for-each loop over this kind of value");
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
		Map<String, String> values = inner instanceof IdentifierTree local ? returnValues.get(unit.element(local)) : null;
		if (values != null) {
			return values.values().stream().map(component -> (Expression) name(component)).toList();
		}
		if (inner instanceof MethodInvocationTree) {
			return List.of(expression(inner));
		}
		throw new Rejection(value, "Several Lua values are returned as they are made: return new "
				+ unit.type(value).toString().replaceAll(".*\\.", "") + "(...), or the call that returns them.");
	}

	/**
	 * {@code throw new SomeException(message)} becomes {@code error(message)}; only the message survives. A caught
	 * error thrown again is raised as it is, {@code error(e, 0)}, so that its message keeps the place it was raised.
	 */
	private Statement throwStatement(ThrowTree thrown) {
		if (thrown.getExpression() instanceof IdentifierTree caught && isCaughtError(caught)) {
			return new Statement.CallStatement(call(name("error"), expression(caught), integer(0)));
		}
		if (!(thrown.getExpression() instanceof NewClassTree creation) || creation.getClassBody() != null) {
			// An exception kept in a local is its message, the value error raises
			return new Statement.CallStatement(call(name("error"), expression(thrown.getExpression())));
		}
		return new Statement.CallStatement(errorCall(creation));
	}

	/** {@code error(message)} for a new exception: its message, or its class's name when it has none. */
	Expression errorCall(NewClassTree creation) {
		return call(name("error"), errorMessage(creation));
	}

	/** A new exception as Lua's error value: its message, or its class's name when it has none. */
	Expression errorMessage(NewClassTree creation) {
		return creation.getArguments().isEmpty()
				? Expression.string(unit.type(creation).toString().replaceAll(".*\\.", ""))
				: strings.asText(creation.getArguments().getFirst());
	}

	// ---- try and catch

	/**
	 * {@code try { ... } catch (RuntimeException e) { ... }} as {@code pcall}: the try block becomes a function that
	 * {@code pcall} runs, and the catch block runs when it raised an error, with {@code e} the error value.
	 *
	 * <pre>{@code
	 * local ok, e = pcall(function()
	 *     ...
	 * end)
	 * if not ok then
	 *     ...
	 * end
	 * }</pre>
	 *
	 * <p>A try block that returns must return on every path, and its value is returned after {@code pcall}:
	 * {@code if ok then return result else ... end}. Lua errors carry a value, not a class, so the one catch clause
	 * names a class that catches everything.
	 */
	private List<Statement> tryStatement(TryTree attempt) {
		List<? extends CatchTree> catches = attempt.getCatches();
		if (catches.size() > 1) {
			throw new Rejection(catches.get(1), "Lua errors carry a value, not a class, so one catch takes every error;"
					+ " catch RuntimeException once, and tell the errors apart inside it.");
		}
		CatchTree handler = catches.isEmpty() ? null : catches.getFirst();
		if (handler != null && !isCatchAll(unit.type(handler.getParameter()))) {
			throw new Rejection(handler.getParameter().getType(), "Lua errors carry a value, not a class, so a catch"
					+ " cannot tell exception classes apart; catch RuntimeException, Exception or Throwable, and tell the"
					+ " errors apart inside it.");
		}
		BlockTree block = attempt.getBlock();
		checkStaysInside(block);
		boolean returns = completesByReturning(block.getStatements(), attempt, "try");
		if (!attempt.getResources().isEmpty()) {
			return withResources(attempt, handler, returns);
		}
		BlockTree finallyBlock = attempt.getFinallyBlock();
		if (finallyBlock != null) {
			return tryFinally(attempt, block(block), returns, handler, block(finallyBlock));
		}
		if (handler == null) {
			throw new IllegalStateException("a try without catch, finally or resources");
		}
		VariableTree parameter = handler.getParameter();
		Expression pcall = call(name("pcall"), new Expression.FunctionExpression(FunctionBody.of(List.of(), block(block))));
		String ok = invent("ok", attempt);
		Element error = unit.element(parameter);
		String errorName = declare(parameter);
		caughtErrors.add(error);
		Block caught = block(handler.getBlock());
		if (!returns) {
			// The error value is named only when the catch block reads it
			List<String> names = uses(handler.getBlock(), error) ? List.of(ok, errorName) : List.of(ok);
			return List.of(
					new Statement.Local(names, List.of(pcall)),
					new Statement.If(List.of(new Statement.If.Branch(new Expression.Unary(UnaryOperator.NOT, name(ok)),
							caught)), null));
		}
		boolean value = inConstructor || returnsValue(block);
		String result = value ? invent("result", attempt) : errorName;
		List<Block.Entry> otherwise = new ArrayList<>();
		if (value && uses(handler.getBlock(), error)) {
			// The second value is the result when pcall succeeded and the error when it did not
			otherwise.add(Block.Entry.of(new Statement.Local(List.of(errorName), List.of(name(result)))));
		}
		otherwise.addAll(caught.entries());
		return List.of(
				new Statement.Local(List.of(ok, result), List.of(pcall)),
				new Statement.If(List.of(new Statement.If.Branch(name(ok),
						Block.of(new Statement.Return(value ? List.of(name(result)) : List.of())))),
						new Block(otherwise, caught.closingComments())));
	}

	/**
	 * Whether a block's every path returns, which a function pcall runs must tell apart from running to its end;
	 * false when no path returns. A block where some paths return and some do not is rejected.
	 */
	private boolean completesByReturning(List<? extends StatementTree> statements, Tree where, String block) {
		boolean returns = statements.stream().anyMatch(BodyLowering::containsReturn);
		if (returns && canCompleteNormally(statements)) {
			throw new Rejection(where, "In Lua the " + block + " block is a function that pcall runs, and Lua cannot tell"
					+ " a function that returned nothing from one that ran to its end; make every path through the "
					+ block + " block return, or none.");
		}
		return returns;
	}

	/**
	 * {@code try ... finally}: the try block, and a catch block, each in a function that {@code pcall} runs, so that
	 * the finally block runs whatever they do; then an error neither handled is raised again, unchanged, and a value
	 * they returned is returned.
	 *
	 * <pre>{@code
	 * local ok, result = pcall(function()
	 *     ...
	 * end)
	 * if not ok then
	 *     ok, result = pcall(function(e)
	 *         ...
	 *     end, result)
	 * end
	 * -- the finally block
	 * if not ok then
	 *     error(result, 0)
	 * end
	 * return result
	 * }</pre>
	 */
	private List<Statement> tryFinally(Tree attempt, Block tryBody, boolean tryReturns, @Nullable CatchTree handler,
			Block finallyBody) {
		boolean catchReturns = false;
		Block caught = null;
		String errorName = null;
		if (handler != null) {
			checkStaysInside(handler.getBlock());
			catchReturns = completesByReturning(handler.getBlock().getStatements(), handler, "catch");
			errorName = declare(handler.getParameter());
			caughtErrors.add(unit.element(handler.getParameter()));
			caught = block(handler.getBlock());
		}
		boolean returns = tryReturns || catchReturns;
		boolean value = returns && (inConstructor || returnsValue(attempt));
		String ok = invent("ok", attempt);
		String result = invent(returns ? "result" : "failure", attempt);
		List<Statement> lowered = new ArrayList<>();
		lowered.add(new Statement.Local(List.of(ok, result), List.of(call(name("pcall"),
				new Expression.FunctionExpression(FunctionBody.of(List.of(), tryBody))))));
		// Which block's value to return when only one of them returns
		String returned = null;
		if (caught != null && tryReturns != catchReturns) {
			returned = invent("returned", attempt);
			lowered.add(new Statement.Local(List.of(returned),
					List.of(tryReturns ? name(ok) : new Expression.BooleanLiteral(false))));
		}
		if (caught != null && handler != null) {
			List<Statement> handling = new ArrayList<>();
			// The catch block's function takes the error only when it reads it
			boolean readsError = uses(handler.getBlock(), unit.element(handler.getParameter()));
			Expression catchFunction = new Expression.FunctionExpression(
					FunctionBody.of(readsError ? List.of(errorName) : List.of(), caught));
			handling.add(new Statement.Assignment(List.of(name(ok), name(result)), List.of(readsError
					? call(name("pcall"), catchFunction, name(result))
					: call(name("pcall"), catchFunction))));
			if (returned != null && catchReturns) {
				handling.add(new Statement.Assignment(List.of(name(returned)), List.of(name(ok))));
			}
			lowered.add(new Statement.If(List.of(new Statement.If.Branch(new Expression.Unary(UnaryOperator.NOT, name(ok)),
					Block.of(handling.toArray(Statement[]::new)))), null));
		}
		finallyBody.entries().forEach(entry -> lowered.add(entry.statement()));
		lowered.add(new Statement.If(List.of(new Statement.If.Branch(new Expression.Unary(UnaryOperator.NOT, name(ok)),
				Block.of(new Statement.CallStatement(call(name("error"), name(result), integer(0)))))), null));
		Statement giveBack = new Statement.Return(value ? List.of(name(result)) : List.of());
		if (returned != null) {
			lowered.add(new Statement.If(List.of(new Statement.If.Branch(name(returned), Block.of(giveBack))), null));
		} else if (returns) {
			lowered.add(giveBack);
		}
		return lowered;
	}

	/**
	 * {@code try (Reader r = open()) { ... }}: the resources declared first, then the try block, with the resources
	 * closed after it, in the order Java closes them, as a finally block that runs before any catch or finally of
	 * the statement's own.
	 */
	private List<Statement> withResources(TryTree attempt, @Nullable CatchTree handler, boolean returns) {
		List<Statement> declared = new ArrayList<>();
		List<Statement> closes = new ArrayList<>();
		for (Tree resource : attempt.getResources()) {
			Expression closed;
			TypeMirror type;
			if (resource instanceof VariableTree variable) {
				declared.addAll(localVariable(variable));
				closed = name(unit.names.luaName(variable.getName().toString()));
				type = unit.type(variable);
			} else {
				closed = expression((ExpressionTree) resource);
				type = unit.type(resource);
			}
			if (types().pluginType(type) == null) {
				throw new Rejection(resource, "Lua has none of " + type + "'s Java code to close it with; use a"
						+ " resource class of the plugin's, which declares close().");
			}
			closes.addFirst(new Statement.CallStatement(new Expression.MethodCall(closed, "close", List.of())));
		}
		List<Statement> inner = new ArrayList<>(declared);
		inner.addAll(tryFinally(attempt, block(attempt.getBlock()), returns, null,
				Block.of(closes.toArray(Statement[]::new))));
		BlockTree finallyBlock = attempt.getFinallyBlock();
		if (handler == null && finallyBlock == null) {
			return inner;
		}
		// A catch or finally of the statement's own sees the resources closed already
		Block innerBlock = Block.of(inner.toArray(Statement[]::new));
		return tryFinally(attempt, innerBlock, returns, handler,
				finallyBlock == null ? Block.of() : block(finallyBlock));
	}

	/** {@code RuntimeException}, {@code Exception} and {@code Throwable}: the classes that catch every Lua error. */
	private boolean isCatchAll(TypeMirror type) {
		return types().is(type, "java.lang.RuntimeException") || types().is(type, "java.lang.Exception")
				|| types().is(type, "java.lang.Throwable");
	}

	/** Whether the tree is the parameter of a catch clause being lowered, the error value {@code pcall} handed back. */
	private boolean isCaughtError(ExpressionTree tree) {
		return tree instanceof IdentifierTree identifier && caughtErrors.contains(unit.element(identifier));
	}

	/** {@code e.getMessage()} is the error value itself, and {@code e.toString()} the value as text. */
	private Expression caughtErrorMethod(MethodInvocationTree call, ExecutableElement method, ExpressionTree error) {
		return switch (method.getSimpleName().toString()) {
			case "getMessage", "getLocalizedMessage" -> expression(error);
			case "toString" -> call(name("tostring"), expression(error));
			default -> throw new Rejection(call, "A Lua error is only its value; read it with "
					+ error + ".getMessage().");
		};
	}

	/** A break, continue or yield that would leave the try block cannot leave the function pcall runs. */
	private static void checkStaysInside(BlockTree block) {
		List<Tree> found = new ArrayList<>();
		new TreeScanner<Void, Integer>() {
			@Override
			public Void visitBreak(BreakTree node, Integer depth) {
				if (depth == 0) {
					found.add(node);
				}
				return null;
			}

			@Override
			public Void visitContinue(ContinueTree node, Integer depth) {
				if (depth == 0) {
					found.add(node);
				}
				return null;
			}

			@Override
			public Void visitYield(YieldTree node, Integer depth) {
				found.add(node);
				return null;
			}

			// A break or continue inside these stays inside them
			@Override
			public Void visitWhileLoop(WhileLoopTree node, Integer depth) {
				return super.visitWhileLoop(node, depth + 1);
			}

			@Override
			public Void visitDoWhileLoop(DoWhileLoopTree node, Integer depth) {
				return super.visitDoWhileLoop(node, depth + 1);
			}

			@Override
			public Void visitForLoop(ForLoopTree node, Integer depth) {
				return super.visitForLoop(node, depth + 1);
			}

			@Override
			public Void visitEnhancedForLoop(EnhancedForLoopTree node, Integer depth) {
				return super.visitEnhancedForLoop(node, depth + 1);
			}

			@Override
			public Void visitSwitch(SwitchTree node, Integer depth) {
				return super.visitSwitch(node, depth + 1);
			}

			@Override
			public Void visitSwitchExpression(SwitchExpressionTree node, Integer depth) {
				return null;
			}

			@Override
			public Void visitLambdaExpression(LambdaExpressionTree node, Integer depth) {
				return null;
			}

			@Override
			public Void visitClass(ClassTree node, Integer depth) {
				return null;
			}
		}.scan(block, 0);
		if (!found.isEmpty()) {
			throw new Rejection(found.getFirst(), "Lua runs the try block as a function, which a break, continue or yield"
					+ " cannot leave; set a flag and act on it after the try.");
		}
	}

	/** Whether the block has a return of its own, not one inside a lambda or class. */
	private static boolean containsReturn(Tree block) {
		return !returns(block).isEmpty();
	}

	/** Whether a return of the block, not one inside a lambda or class, returns a value. */
	private static boolean returnsValue(Tree block) {
		return returns(block).stream().anyMatch(returned -> returned.getExpression() != null);
	}

	private static List<ReturnTree> returns(Tree block) {
		List<ReturnTree> found = new ArrayList<>();
		new TreeScanner<Void, Void>() {
			@Override
			public Void visitReturn(ReturnTree node, Void unused) {
				found.add(node);
				return null;
			}

			@Override
			public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
				return null;
			}

			@Override
			public Void visitClass(ClassTree node, Void unused) {
				return null;
			}
		}.scan(block, null);
		return found;
	}

	/** Whether the tree refers to the element anywhere. */
	private boolean uses(Tree tree, Element element) {
		Boolean found = new TreeScanner<Boolean, Void>() {
			@Override
			public Boolean visitIdentifier(IdentifierTree node, Void unused) {
				return refersTo(node, element);
			}

			@Override
			public Boolean reduce(Boolean first, Boolean second) {
				return Boolean.TRUE.equals(first) || Boolean.TRUE.equals(second);
			}
		}.scan(tree, null);
		return Boolean.TRUE.equals(found);
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
			case NewClassTree creation -> creation(creation);
			case BinaryTree operation -> binaryOperation(operation);
			case UnaryTree operation -> unaryOperation(operation);
			case ConditionalExpressionTree conditional -> conditional(conditional);
			case ParenthesizedTree parenthesized -> expression(parenthesized.getExpression());
			case TypeCastTree cast -> cast(cast);
			case ArrayAccessTree access ->
					new Expression.Index(expression(access.getExpression()), index(access.getIndex()));
			case NewArrayTree array -> newArray(array);
			case LambdaExpressionTree lambda -> lambda(lambda);
			case MemberReferenceTree reference -> memberReference(reference);
			case InstanceOfTree instance -> instanceOf(instance);
			case AssignmentTree _, CompoundAssignmentTree _ ->
					throw new Rejection(tree, "Lua has no assignment expressions; put the assignment on its own line.");
			case SwitchExpressionTree switched -> throw new Rejection(switched, "In Lua a switch is a statement; assign"
					+ " the switch to a local, or return it, instead of using it inside an expression.");
			default -> throw Rejection.notYet(tree, "This kind of expression (" + tree.getKind() + ")");
		};
	}

	/**
	 * {@code x instanceof T}, and with a pattern, {@code x instanceof T t}, whose variable stands for {@code x}, which
	 * must read the same each time.
	 */
	private Expression instanceOf(InstanceOfTree instance) {
		ExpressionTree operand = instance.getExpression();
		Expression value = expression(operand);
		Tree pattern = instance.getPattern();
		if (pattern == null) {
			return typeTest(value, unit.type(instance.getType()), instance);
		}
		VariableTree variable = patternVariable(pattern);
		if (containsCall(operand)) {
			throw new Rejection(operand, "The pattern's variable stands for this value, which Lua would read again each"
					+ " time; store it in a local first.");
		}
		declare(variable);
		alias(variable, value);
		return typeTest(value, unit.type(variable), instance);
	}

	/** A type pattern's variable; a record pattern, which would take the record apart, has no Lua form. */
	private static VariableTree patternVariable(Tree pattern) {
		if (pattern instanceof BindingPatternTree binding) {
			return binding.getVariable();
		}
		throw new Rejection(pattern, "Lua has no patterns that take a record apart; test the type, then read the"
				+ " record's components.");
	}

	/**
	 * A test that a value is of a type, as Java's instanceof. A class of the plugin's is its objects' metatable,
	 * {@code getmetatable(x) == Square}, through the helper {@code isInstance} for a class that others extend; an
	 * interface is the classes that implement it; a string, number or boolean is Lua's own type; a simple enum's
	 * constant is one of its strings, {@code Mode[x] == x}.
	 */
	Expression typeTest(Expression value, TypeMirror type, Tree where) {
		if (types().isString(type) || types().isChar(type)) {
			return binary(call(name("type"), value), BinaryOperator.EQUAL, Expression.string("string"));
		}
		if (types().isBoolean(type)) {
			return binary(call(name("type"), value), BinaryOperator.EQUAL, Expression.string("boolean"));
		}
		if (types().isIntegral(type) || types().isFloating(type)) {
			return binary(call(Expression.field(name("math"), "type"), value), BinaryOperator.EQUAL,
					Expression.string(types().isIntegral(type) ? "integer" : "float"));
		}
		if (types().is(type, "java.lang.Number")) {
			return binary(call(name("type"), value), BinaryOperator.EQUAL, Expression.string("number"));
		}
		TypeElement plugin = types().pluginType(type);
		if (plugin == null) {
			throw new Rejection(where, "Lua cannot tell the client's objects, or lists, maps and other tables, apart by"
					+ " type; test what the value holds instead.");
		}
		if (plugin.getKind() == ElementKind.ENUM && !types().isObjectEnum(type)) {
			return binary(new Expression.Index(unit.moduleReference(plugin), value), BinaryOperator.EQUAL, value);
		}
		if (JavaTypes.isRecord(plugin) && !types().isObjectRecord(plugin)) {
			throw new Rejection(where, "A record with only its components is a plain Lua table, which Lua cannot tell from"
					+ " other tables; give the record a method, which makes its values objects of a class, or test what"
					+ " the value holds instead.");
		}
		if (types().isFunctionalPluginInterface(plugin)) {
			return binary(call(name("type"), value), BinaryOperator.EQUAL, Expression.string("function"));
		}
		List<TypeElement> roots = plugin.getKind() == ElementKind.INTERFACE ? types().instanceRoots(plugin)
				: List.of(plugin);
		Expression test = null;
		for (TypeElement root : roots) {
			Expression one;
			if (types().hasPluginSubclass(root)) {
				unit.use(Helper.IS_INSTANCE);
				one = Helper.IS_INSTANCE.invoke(value, unit.moduleReference(root));
			} else {
				one = binary(call(name("getmetatable"), value), BinaryOperator.EQUAL, unit.moduleReference(root));
			}
			test = test == null ? one : binary(test, BinaryOperator.OR, one);
		}
		if (test == null) {
			throw new Rejection(where, "No class of the plugin's implements " + plugin.getSimpleName() + ", so no value"
					+ " is one.");
		}
		return test;
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

	/** An integer literal as written where Lua reads it the same: hexadecimal stays, binary and octal become decimal. */
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
			throw new Rejection(identifier, "The values a Lua function returns exist only at its call; read them through "
					+ javaName + "'s components, such as " + javaName + "."
					+ record.getRecordComponents().getFirst().getSimpleName() + "().");
		}
		return switch (element.getKind()) {
			case LOCAL_VARIABLE, PARAMETER, RESOURCE_VARIABLE, EXCEPTION_PARAMETER, BINDING_VARIABLE ->
					aliases.getOrDefault(element, name(unit.names.luaName(javaName)));
			case FIELD -> field((VariableElement) element, null, identifier);
			case ENUM_CONSTANT -> enumConstant((VariableElement) element, identifier);
			default -> throw Rejection.notYet(identifier, "Using " + javaName + " as a value");
		};
	}

	/**
	 * An enum constant: of the plugin's, {@code Mode.FAST}, whose value is the string of its name; of the API,
	 * {@code GameState.GAME}, whose value is the client's.
	 */
	private Expression enumConstant(VariableElement constant, Tree where) {
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
	private static boolean isThis(@Nullable ExpressionTree receiver) {
		return receiver instanceof IdentifierTree identifier
				&& (identifier.getName().contentEquals("this") || identifier.getName().contentEquals("super"));
	}

	private static boolean isSuper(@Nullable ExpressionTree receiver) {
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
				throw new Rejection(where, "A class inside a method takes self for its own objects, so its methods cannot"
						+ " read the outer object's members as well; read what they need into a local before the class.");
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
	Expression field(VariableElement field, @Nullable ExpressionTree receiver, Tree where) {
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
		return library.staticField(field, where);
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
		ExpressionTree receiver = call.getMethodSelect() instanceof MemberSelectTree select ? select.getExpression() : null;
		List<String> entryNames = receiver instanceof IdentifierTree entry ? entries.get(unit.element(entry)) : null;
		if (entryNames != null) {
			List<String> names = entryNames;
			return switch (method.getSimpleName().toString()) {
				case "getKey" -> name(names.get(0));
				case "getValue" -> name(names.get(1));
				default -> throw Rejection.notYet(call, "Calling " + method.getSimpleName() + " on a map entry");
			};
		}
		Map<String, String> values = receiver instanceof IdentifierTree local ? returnValues.get(unit.element(local)) : null;
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
			return caughtErrorMethod(call, method, receiver);
		}
		if (types().isApi(owner)) {
			return api.invocation(call, method, owner, receiver);
		}
		if (types().isPluginType(owner)) {
			return pluginCall(call, method, owner, receiver);
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
	 * A call of a plugin method: {@code Module.f(...)} for a static method, {@code object:f(...)} for an instance
	 * method, {@code f(object, ...)} for a private one, which is a local function, {@code Base.f(self, ...)} through
	 * {@code super}, and {@code f(...)} for the method of an interface whose values are plain functions.
	 */
	private Expression pluginCall(MethodInvocationTree call, ExecutableElement method, TypeElement owner,
			@Nullable ExpressionTree receiver) {
		unit.checkReachable(owner, call);
		// values() and valueOf(), which javac gives every enum; a static method the enum declares is a plain one
		if (owner.getKind() == ElementKind.ENUM && method.getModifiers().contains(Modifier.STATIC)
				&& !unit.declaredInSource(method)) {
			return enumStatic(call, method, owner);
		}
		if (unit.isLifecycle(method)) {
			throw new Rejection(call, method.getSimpleName() + "() runs once, when the plugin "
					+ (method.getSimpleName().contentEquals("startUp") ? "starts" : "stops") + "; move what it shares into"
					+ " a method of its own and call that.");
		}
		List<Expression> arguments = arguments(method, call.getArguments());
		if (types().configGroup(owner) != null) {
			// Settings are functions of the settings module, called with a dot
			return new Expression.Call(Expression.field(unit.moduleReference(owner), types().memberName(method)),
					arguments);
		}
		if (method.getModifiers().contains(Modifier.STATIC) || unit.isEntry(owner)) {
			return new Expression.Call(unit.staticMember(owner, method), arguments);
		}
		if (isSuper(receiver)) {
			TypeElement superclass = unit.pluginSuperclass(unit.enclosingClass(call));
			if (superclass == null) {
				throw new IllegalStateException("super call without a plugin superclass at " + unit.origin(call));
			}
			List<Expression> withSelf = new ArrayList<>();
			withSelf.add(self(call));
			withSelf.addAll(arguments);
			return new Expression.Call(Expression.field(unit.moduleReference(superclass), types().memberName(method)),
					withSelf);
		}
		Expression target = receiver == null ? receiverFor(owner, call)
				: isThis(receiver) ? self(call)
				: expression(receiver);
		if (JavaTypes.isRecord(owner)) {
			if (JavaTypes.isRecordAccessor(method)) {
				return Expression.field(target, types().memberName(method));
			}
			// equals, hashCode and toString that the record does not declare itself
			if (!types().isObjectRecord(owner) || !unit.declaredInSource(method)) {
				throw new Rejection(call, "A record is a plain Lua table, compared by identity and without methods; use"
						+ " its components instead.");
			}
		}
		if (types().isFunctionalPluginInterface(owner)) {
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
		return new Expression.MethodCall(target, types().memberName(method), arguments);
	}

	/**
	 * An enum's {@code values()}, a new list of its constants in order as Java's is a new array, and
	 * {@code valueOf(name)}, the constant's table entry; Lua gives nil for a name that is not a constant.
	 */
	private Expression enumStatic(MethodInvocationTree call, ExecutableElement method, TypeElement owner) {
		String name = method.getSimpleName().toString();
		if (name.equals("values") && call.getArguments().isEmpty()) {
			List<Expression.TableField> constants = ElementFilter.fieldsIn(owner.getEnclosedElements()).stream()
					.filter(constant -> constant.getKind() == ElementKind.ENUM_CONSTANT)
					.map(constant -> (Expression.TableField) new Expression.TableField.Positional(enumConstant(constant, call)))
					.toList();
			return new Expression.Table(constants);
		}
		if (name.equals("valueOf") && call.getArguments().size() == 1) {
			return new Expression.Index(unit.moduleReference(owner), expression(call.getArguments().getFirst()));
		}
		throw Rejection.notYet(call, "The enum method " + name);
	}

	private Expression creation(NewClassTree creation) {
		if (creation.getClassBody() != null) {
			return anonymous(creation, creation.getClassBody());
		}
		ExecutableElement constructor = (ExecutableElement) unit.element(creation);
		TypeElement type = JavaTypes.owner(constructor);
		if (types().isApi(type)) {
			return api.construct(creation, constructor, type);
		}
		if (types().isReturnValues(type.asType())) {
			throw new Rejection(creation, type.getSimpleName() + " stands for several Lua values, which exist only where"
					+ " a function returns them; return it directly, return new " + type.getSimpleName() + "(...);.");
		}
		if (JavaTypes.isRecord(type) && !types().isObjectRecord(type)) {
			return record(type, creation.getArguments());
		}
		if (types().declaredModule(type) != null) {
			throw new Rejection(creation, "A @LuaModule declaration has no Java constructor; call the module's own"
					+ " constructor, declared as a static native method with @LuaName(\"new\").");
		}
		if (types().isPluginEntry(type) || types().isDevEntry(type)) {
			throw new Rejection(creation, "The client creates the plugin class, once, when the plugin starts; use its"
					+ " members directly instead of creating another.");
		}
		if (types().isPluginType(type)) {
			List<Expression> arguments = new ArrayList<>();
			if (JavaTypes.isInner(type) && type.getEnclosingElement() instanceof TypeElement outer) {
				// An inner object gets its outer one first: outer.new Inner(), or the object making it
				ExpressionTree enclosing = creation.getEnclosingExpression();
				arguments.add(enclosing != null ? expression(enclosing) : receiverFor(outer, creation));
			}
			arguments.addAll(arguments((ExecutableElement) unit.element(creation), creation.getArguments()));
			return new Expression.Call(Expression.field(unit.moduleReference(type), "new"), arguments);
		}
		return library.construct(creation, type);
	}

	/**
	 * An anonymous class. One that implements a functional interface with its one method, no fields and no use of
	 * {@code this} is a function, as a lambda is. Any other is an object: a table of the class's fields and methods,
	 * with the interfaces' default methods it does not replace, or for a subclass its superclass's object given them
	 * through the helper {@code withMembers}.
	 */
	private Expression anonymous(NewClassTree creation, ClassTree body) {
		TypeElement anonymous = (TypeElement) unit.element(body);
		TypeElement base = (TypeElement) ((DeclaredType) unit.type(creation.getIdentifier())).asElement();
		List<MethodTree> methods = new ArrayList<>();
		List<VariableTree> fields = new ArrayList<>();
		for (Tree member : body.getMembers()) {
			switch (member) {
				case MethodTree method when unit.declaredInSource(unit.element(method)) -> {
					if (method.getModifiers().getFlags().contains(Modifier.PRIVATE)) {
						throw new Rejection(method, "A private method of an anonymous class would be a local of the whole"
								+ " file in Lua; make it a method the class's others call on self.");
					}
					methods.add(method);
				}
				case MethodTree _ -> {
					// The constructor javac gives the class
				}
				case VariableTree field -> fields.add(field);
				default -> throw new Rejection(member, "An anonymous class here holds fields and methods only; move this"
						+ " into a class of its own.");
			}
		}
		boolean functionalBase = base.getKind() == ElementKind.INTERFACE
				&& (types().isFunctionalPluginInterface(base)
						|| (!types().isPluginType(base) && base.getAnnotation(FunctionalInterface.class) != null));
		if (functionalBase && methods.size() == 1 && fields.isEmpty() && !usesThis(body)) {
			MethodTree method = methods.getFirst();
			BodyLowering inner = new BodyLowering(unit, hasSelf, false);
			inner.inherit(this);
			List<String> parameters = method.getParameters().stream().map(inner::declare).toList();
			BlockTree methodBody = method.getBody();
			if (methodBody == null) {
				throw new IllegalStateException("an anonymous class's method without a body");
			}
			return new Expression.FunctionExpression(FunctionBody.of(parameters, inner.block(methodBody)));
		}
		if (functionalBase && !types().isPluginType(base)) {
			throw new Rejection(creation, base.getSimpleName() + " stands for a function in Lua, which carries no"
					+ " fields or other methods; write it as a lambda, or as a class of the plugin's.");
		}
		List<Expression.TableField> members = new ArrayList<>();
		BodyLowering inner = new BodyLowering(unit, true, false);
		inner.inherit(this);
		inner.anonymousObject = true;
		for (VariableTree field : fields) {
			inner.declare(field);
			ExpressionTree initializer = field.getInitializer();
			Expression value = initializer == null ? unit.defaultValue(unit.type(field)) : inner.expression(initializer);
			if (value != null) {
				members.add(new Expression.TableField.Named(types().memberName(unit.element(field)), value));
			}
		}
		for (MethodTree method : methods) {
			BodyLowering methodLowering = new BodyLowering(unit, true, false);
			methodLowering.inherit(this);
			methodLowering.anonymousObject = true;
			List<String> parameters = new ArrayList<>(List.of("self"));
			method.getParameters().forEach(parameter -> parameters.add(methodLowering.declare(parameter)));
			BlockTree methodBody = method.getBody();
			if (methodBody == null) {
				throw new IllegalStateException("an anonymous class's method without a body");
			}
			members.add(new Expression.TableField.Named(types().memberName(unit.element(method)),
					new Expression.FunctionExpression(FunctionBody.of(parameters, methodLowering.block(methodBody)))));
		}
		Set<Element> own = new HashSet<>(unit.elements.getAllMembers(anonymous));
		for (TypeMirror implemented : anonymous.getInterfaces()) {
			if (((DeclaredType) implemented).asElement() instanceof TypeElement face && types().isPluginType(face)) {
				for (ExecutableElement method : ElementFilter.methodsIn(face.getEnclosedElements())) {
					if (method.getModifiers().contains(Modifier.DEFAULT) && own.contains(method)) {
						members.add(new Expression.TableField.Named(types().memberName(method),
								Expression.field(unit.moduleReference(face), types().memberName(method))));
					}
				}
			}
		}
		if (!(types().isPluginType(base) && base.getKind() == ElementKind.CLASS)) {
			// A table of fields and methods is an object already
			return new Expression.Table(members);
		}
		Expression object = new Expression.Call(Expression.field(unit.moduleReference(base), "new"),
				arguments(creation.getArguments()));
		unit.use(Helper.WITH_MEMBERS);
		return Helper.WITH_MEMBERS.invoke(object, new Expression.Table(members));
	}

	/** Whether code uses {@code this}, outside the classes inside it. */
	private static boolean usesThis(Tree tree) {
		Boolean found = new TreeScanner<Boolean, Void>() {
			@Override
			public Boolean visitIdentifier(IdentifierTree node, Void unused) {
				return node.getName().contentEquals("this");
			}

			@Override
			public Boolean visitClass(ClassTree node, Void unused) {
				// The class itself is the one being asked about; a class inside it has its own this
				return node.equals(tree) ? super.visitClass(node, null) : false;
			}

			@Override
			public Boolean reduce(Boolean first, Boolean second) {
				return Boolean.TRUE.equals(first) || Boolean.TRUE.equals(second);
			}
		}.scan(tree, null);
		return Boolean.TRUE.equals(found);
	}

	/** A record as the plain Lua table it is: {@code { spriteId = id, visible = shown }}, one field per component. */
	private Expression record(TypeElement type, List<? extends ExpressionTree> values) {
		List<Expression.TableField> fields = new ArrayList<>();
		for (int index = 0; index < values.size(); index++) {
			String component = types().memberName(type.getRecordComponents().get(index).getAccessor());
			fields.add(new Expression.TableField.Named(component, expression(values.get(index))));
		}
		return new Expression.Table(fields);
	}

	private Expression binaryOperation(BinaryTree operation) {
		if (operation.getKind() == Tree.Kind.PLUS && types().isString(unit.type(operation))) {
			return strings.concatenation(operation);
		}
		if (operation.getKind() == Tree.Kind.PLUS) {
			// i + 1, where Lua's loop variable already is Java's i + 1
			ExpressionTree counted = countedFromOne(operation.getLeftOperand(), operation.getRightOperand());
			if (counted == null) {
				counted = countedFromOne(operation.getRightOperand(), operation.getLeftOperand());
			}
			if (counted != null) {
				return expression(counted);
			}
		}
		Expression left = expression(operation.getLeftOperand());
		return operation(operation.getKind(), operation.getLeftOperand(), left, operation.getRightOperand(),
				unit.type(operation.getLeftOperand()), operation);
	}

	/**
	 * A Java operator applied to an already lowered left operand: arithmetic, comparison, logic or bits. Integer
	 * {@code /} and {@code %} keep Java's rounding toward zero, which Lua's {@code //} and {@code %} share only for
	 * non-negative operands.
	 */
	private Expression operation(Tree.Kind kind, ExpressionTree leftTree, Expression left, ExpressionTree rightTree,
			TypeMirror leftType, Tree where) {
		Expression right = expression(rightTree);
		TypeMirror rightType = unit.type(rightTree);
		// A char in arithmetic is its number, a whole one
		boolean integral = (types().isIntegral(leftType) || types().isChar(leftType))
				&& (types().isIntegral(rightType) || types().isChar(rightType));
		boolean bothNonNegative = integral && knownNonNegative(leftTree) && knownNonNegative(rightTree);
		boolean logical = types().isBoolean(leftType);
		return switch (kind) {
			case PLUS -> binary(left, BinaryOperator.ADD, right);
			case MINUS -> binary(left, BinaryOperator.SUBTRACT, right);
			case MULTIPLY -> binary(left, BinaryOperator.MULTIPLY, right);
			case DIVIDE -> {
				if (!integral) {
					yield binary(left, BinaryOperator.DIVIDE, right);
				}
				if (bothNonNegative) {
					yield binary(left, BinaryOperator.FLOOR_DIVIDE, right);
				}
				unit.use(Helper.QUOTIENT);
				yield Helper.QUOTIENT.invoke(left, right);
			}
			case REMAINDER -> bothNonNegative
					? binary(left, BinaryOperator.MODULO, right)
					: call(Expression.field(name("math"), "fmod"), left, right);
			case LESS_THAN -> binary(left, BinaryOperator.LESS, right);
			case GREATER_THAN -> binary(left, BinaryOperator.GREATER, right);
			case LESS_THAN_EQUAL -> binary(left, BinaryOperator.LESS_EQUAL, right);
			case GREATER_THAN_EQUAL -> binary(left, BinaryOperator.GREATER_EQUAL, right);
			case EQUAL_TO -> binary(left, BinaryOperator.EQUAL, right);
			case NOT_EQUAL_TO -> binary(left, BinaryOperator.NOT_EQUAL, right);
			case CONDITIONAL_AND -> binary(left, BinaryOperator.AND, right);
			case CONDITIONAL_OR -> binary(left, BinaryOperator.OR, right);
			case AND, OR -> {
				if (!logical) {
					yield binary(left, kind == Tree.Kind.AND ? BinaryOperator.BIT_AND : BinaryOperator.BIT_OR, right);
				}
				if (containsCall(rightTree)) {
					throw new Rejection(where, "Java evaluates both sides of & and |, but Lua's and/or skip the right"
							+ " side when the left decides; evaluate the right side into a local first.");
				}
				yield binary(left, kind == Tree.Kind.AND ? BinaryOperator.AND : BinaryOperator.OR, right);
			}
			case XOR -> logical
					? binary(left, BinaryOperator.NOT_EQUAL, right)
					: binary(left, BinaryOperator.BIT_XOR, right);
			case LEFT_SHIFT -> binary(left, BinaryOperator.SHIFT_LEFT, right);
			case RIGHT_SHIFT -> knownNonNegative(leftTree)
					? binary(left, BinaryOperator.SHIFT_RIGHT, right)
					// Java's >> keeps the sign, which is floor division by a power of two; Lua's >> is a logical shift
					: binary(left, BinaryOperator.FLOOR_DIVIDE, powerOfTwo(right));
			case UNSIGNED_RIGHT_SHIFT -> types().isLong(leftType)
					? binary(left, BinaryOperator.SHIFT_RIGHT, right)
					: binary(binary(left, BinaryOperator.BIT_AND, new Expression.NumberLiteral("0xFFFFFFFF")),
							BinaryOperator.SHIFT_RIGHT, right);
			default -> throw Rejection.notYet(where, "The operator " + kind);
		};
	}

	/** The variable of {@code variable + 1} when it is a loop variable that counts from 1 in Lua; otherwise null. */
	private @Nullable ExpressionTree countedFromOne(ExpressionTree variable, ExpressionTree one) {
		return variable instanceof IdentifierTree identifier && unit.countsFromOne(unit.element(identifier))
				&& one instanceof LiteralTree literal && Integer.valueOf(1).equals(literal.getValue()) ? variable : null;
	}

	/** {@code 1 << n}, folded to a number when n is a literal. */
	private static Expression powerOfTwo(Expression exponent) {
		Long literal = integerValue(exponent);
		if (literal != null && literal >= 0 && literal < 63) {
			return integer(1L << literal);
		}
		return binary(integer(1), BinaryOperator.SHIFT_LEFT, exponent);
	}

	private Expression unaryOperation(UnaryTree operation) {
		ExpressionTree operand = operation.getExpression();
		return switch (operation.getKind()) {
			case UNARY_MINUS -> {
				// javac already folds the minus into the literals -2147483648 and -9223372036854775808
				if (operand instanceof LiteralTree literal && literal.getValue() instanceof Number number
						&& number.longValue() < 0) {
					yield expression(operand);
				}
				yield new Expression.Unary(UnaryOperator.NEGATE, expression(operand));
			}
			case UNARY_PLUS -> expression(operand);
			case LOGICAL_COMPLEMENT -> {
				Expression negated = expression(operand);
				// not (a == b) reads better as a ~= b, and always means the same
				if (negated instanceof Expression.Binary comparison && (comparison.operator() == BinaryOperator.EQUAL
						|| comparison.operator() == BinaryOperator.NOT_EQUAL)) {
					yield binary(comparison.left(), comparison.operator() == BinaryOperator.EQUAL
							? BinaryOperator.NOT_EQUAL
							: BinaryOperator.EQUAL, comparison.right());
				}
				yield new Expression.Unary(UnaryOperator.NOT, negated);
			}
			case BITWISE_COMPLEMENT -> new Expression.Unary(UnaryOperator.BIT_NOT, expression(operand));
			default -> throw new Rejection(operation, "Lua has no increment or decrement expressions; put i++ on its"
					+ " own line.");
		};
	}

	/**
	 * {@code c ? a : b} as {@code c and a or b}, which Lua evaluates the same way only when {@code a} can be neither
	 * false nor nil. Other conditionals are lowered to an if statement where they are a statement's value; anywhere
	 * else they are rejected.
	 */
	private Expression conditional(ConditionalExpressionTree conditional) {
		if (!safeAsAndOr(conditional)) {
			throw new Rejection(conditional, "In Lua this conditional would need its true branch never to be false or"
					+ " nil; assign the conditional to a local first.");
		}
		return binary(
				binary(expression(conditional.getCondition()), BinaryOperator.AND,
						expression(conditional.getTrueExpression())),
				BinaryOperator.OR,
				expression(conditional.getFalseExpression()));
	}

	private boolean safeAsAndOr(ConditionalExpressionTree conditional) {
		ExpressionTree whenTrue = conditional.getTrueExpression();
		TypeMirror type = unit.type(whenTrue);
		if (type.getKind().isPrimitive()) {
			return type.getKind() != TypeKind.BOOLEAN;
		}
		return switch (whenTrue) {
			case LiteralTree literal -> literal.getValue() != null && !Boolean.FALSE.equals(literal.getValue());
			case NewClassTree _, NewArrayTree _, LambdaExpressionTree _ -> true;
			case BinaryTree binary -> binary.getKind() == Tree.Kind.PLUS && types().isString(type);
			default -> false;
		};
	}

	private Expression cast(TypeCastTree cast) {
		TypeMirror target = unit.type(cast.getType());
		TypeMirror source = unit.type(cast.getExpression());
		// A cast between char and number is one of the conversions expression() adds
		if (types().isIntegral(target) && types().isFloating(source)) {
			unit.use(Helper.TO_INTEGER);
			return Helper.TO_INTEGER.invoke(expression(cast.getExpression()));
		}
		return expression(cast.getExpression());
	}

	/** A Java index as a Lua one: one more, folded where it can be, unless the loop already counts from 1. */
	Expression index(ExpressionTree javaIndex) {
		if (javaIndex instanceof IdentifierTree identifier && unit.countsFromOne(unit.element(identifier))) {
			return name(unit.names.luaName(identifier.getName().toString()));
		}
		return plusOne(expression(javaIndex));
	}

	/** The element of {@code list.get(i)}: {@code list[i + 1]}, or {@code list[i]} when the loop counts from 1. */
	Expression listElement(Expression list, ExpressionTree javaIndex) {
		return new Expression.Index(list, index(javaIndex));
	}

	private Expression newArray(NewArrayTree array) {
		if (array.getInitializers() != null) {
			List<Expression.TableField> fields = new ArrayList<>();
			for (ExpressionTree element : array.getInitializers()) {
				fields.add(new Expression.TableField.Positional(expression(element)));
			}
			return new Expression.Table(fields);
		}
		TypeMirror component = ((ArrayType) unit.type(array)).getComponentType();
		for (int dimension = 1; dimension < array.getDimensions().size(); dimension++) {
			component = ((ArrayType) component).getComponentType();
		}
		Expression filler;
		if (types().isIntegral(component)) {
			filler = integer(0);
		} else if (types().isFloating(component)) {
			filler = new Expression.NumberLiteral("0.0");
		} else if (types().isBoolean(component)) {
			filler = new Expression.BooleanLiteral(false);
		} else if (types().isChar(component)) {
			filler = Expression.string("\0");
		} else {
			throw new Rejection(array, "A new reference array is full of null, and Lua arrays cannot hold nil; use a"
					+ " List and add elements.");
		}
		if (array.getDimensions().size() > 1) {
			// new int[3][4] is three rows of four zeros
			List<Expression> arguments = new ArrayList<>(List.of(filler));
			array.getDimensions().forEach(size -> arguments.add(expression(size)));
			unit.use(Helper.GRID);
			return Helper.GRID.invoke(arguments.toArray(Expression[]::new));
		}
		unit.use(Helper.FILLED);
		return Helper.FILLED.invoke(expression(array.getDimensions().getFirst()), filler);
	}

	private Expression lambda(LambdaExpressionTree lambda) {
		checkFunctionTarget(lambda);
		BodyLowering inner = new BodyLowering(unit, hasSelf, false);
		inner.inherit(this);
		List<String> parameters = lambda.getParameters().stream().map(inner::declare).toList();
		// Lua drops the arguments a function does not name, so unnamed parameters at the end are left out:
		// (_, x, _) -> ... is function(_, x)
		int named = parameters.size();
		while (named > 0 && parameters.get(named - 1).equals("_")) {
			named--;
		}
		parameters = List.copyOf(parameters.subList(0, named));
		// An event that passes nothing calls its callbacks without a value, so the payload parameter has no Lua form
		if (lambda.getParameters().size() == 1) {
			VariableTree payload = lambda.getParameters().getFirst();
			JavaTypes.Event event = eventWithoutPayload(payload);
			if (event != null) {
				if (uses(lambda.getBody(), unit.element(payload))) {
					throw new Rejection(payload, "The " + event.name() + " event passes nothing, so its payload has nothing"
							+ " to read; leave " + payload.getName() + " unused.");
				}
				parameters = List.of();
			}
		}
		Block body;
		if (lambda.getBody() instanceof BlockTree block) {
			body = inner.block(block);
		} else {
			ExpressionTree expression = (ExpressionTree) lambda.getBody();
			body = blockOf(unit.returnsVoid(lambda) ? inner.expressionStatement(expression) : inner.returned(expression));
		}
		return new Expression.FunctionExpression(FunctionBody.of(parameters, body));
	}

	/**
	 * A lambda written where Lua can take its code in place, as the body of the loop a {@code forEach} or
	 * {@code removeIf} becomes: its parameters are declared here, as the loop's variables, so its code reads them by its
	 * own names. Null for anything else: a method reference, a function held in a variable, or a block that returns
	 * early, which only a function can do.
	 *
	 * @param parameters the Lua names of the lambda's parameters
	 * @param body the lambda's code as statements; for an expression body, the expression as a statement
	 * @param value an expression body, which a predicate's loop tests
	 */
	record InlinedLambda(List<String> parameters, Block body, @Nullable ExpressionTree value) {
	}

	@Nullable InlinedLambda inline(ExpressionTree function) {
		return inline(function, null);
	}

	/**
	 * A lambda's code in place, as {@link #inline(ExpressionTree)}, with its parameters standing for the given locals
	 * instead of declaring their own, as a stream's stages read the loop's element.
	 */
	@Nullable InlinedLambda inline(ExpressionTree function, @Nullable List<String> standingFor) {
		if (!(withoutParentheses(function) instanceof LambdaExpressionTree lambda) || returnsEarly(lambda)) {
			return null;
		}
		List<String> parameters = new ArrayList<>();
		for (int index = 0; index < lambda.getParameters().size(); index++) {
			VariableTree parameter = lambda.getParameters().get(index);
			String declared = declare(parameter);
			if (standingFor != null) {
				alias(parameter, standingFor.get(index));
				declared = standingFor.get(index);
			}
			parameters.add(declared);
		}
		if (lambda.getBody() instanceof BlockTree block) {
			return new InlinedLambda(parameters, block(block), null);
		}
		ExpressionTree expression = (ExpressionTree) lambda.getBody();
		Block asStatement = unit.returnsVoid(lambda) ? blockOf(expressionStatement(expression)) : Block.of();
		return new InlinedLambda(parameters, asStatement, expression);
	}

	/**
	 * A function value called on arguments. A method reference's small function,
	 * {@code function(npc) return self:isTarget(npc) end}, is opened up into the call it makes,
	 * {@code self:isTarget(x)}; see {@link Inlining}. Anything else is called as it is.
	 */
	Expression applied(Expression function, List<Expression> arguments) {
		Expression opened = Inlining.value(function, arguments);
		return opened != null ? opened : new Expression.Call(function, arguments);
	}

	/** Whether a block lambda returns anywhere but at its end, outside the lambdas and classes inside it. */
	static boolean returnsEarly(LambdaExpressionTree lambda) {
		if (!(lambda.getBody() instanceof BlockTree block)) {
			return false;
		}
		Boolean found = new TreeScanner<Boolean, Void>() {
			@Override
			public Boolean visitReturn(ReturnTree node, Void unused) {
				return true;
			}

			@Override
			public Boolean visitLambdaExpression(LambdaExpressionTree node, Void unused) {
				return false;
			}

			@Override
			public Boolean visitClass(ClassTree node, Void unused) {
				return false;
			}

			@Override
			public Boolean reduce(Boolean first, Boolean second) {
				return Boolean.TRUE.equals(first) || Boolean.TRUE.equals(second);
			}
		}.scan(block, null);
		return Boolean.TRUE.equals(found);
	}

	/** The event whose payload the parameter is, when that event passes nothing; null for any other parameter. */
	private JavaTypes.@Nullable Event eventWithoutPayload(VariableTree parameter) {
		if (unit.type(parameter) instanceof DeclaredType declared && declared.asElement() instanceof TypeElement payload
				&& ElementFilter.fieldsIn(payload.getEnclosedElements()).isEmpty()) {
			return types().event(payload);
		}
		return null;
	}

	/**
	 * A lambda or method reference is a Lua function, which cannot stand in for an interface that a class implements:
	 * code calls that interface's methods with a colon, as methods of an object.
	 */
	private void checkFunctionTarget(Tree function) {
		TypeElement target = types().pluginType(unit.type(function));
		if (target != null && types().isImplemented(target)) {
			throw new Rejection(function, target.getSimpleName() + " is implemented by a class, so its values are Lua"
					+ " objects with methods, which a function cannot stand in for; implement it with a class here too.");
		}
	}

	/** What a lambda's code may use of the code around it: the facts about its locals, which it sees as upvalues. */
	private void inherit(BodyLowering outer) {
		nonNegative.addAll(outer.nonNegative);
		entries.putAll(outer.entries);
		returnValues.putAll(outer.returnValues);
		caughtErrors.addAll(outer.caughtErrors);
		invented.addAll(outer.invented);
		aliases.putAll(outer.aliases);
	}

	/** A method reference as a Lua function; see {@link MethodReferences}. */
	private Expression memberReference(MemberReferenceTree reference) {
		checkFunctionTarget(reference);
		return methodReferences.lower(reference);
	}

	// ---- Facts about the source

	/**
	 * Whether an integer expression is known never to be negative: a non-negative literal, a size or length, an
	 * absolute value, a loop variable counting up from a non-negative start, or a final local or constant with such
	 * a value. Sums, products and quotients of those stay non-negative.
	 */
	boolean knownNonNegative(ExpressionTree tree) {
		if (types().isChar(unit.type(tree))) {
			// A char's number, which is never negative
			return true;
		}
		return switch (tree) {
			case LiteralTree literal -> literal.getValue() instanceof Number number && number.longValue() >= 0;
			case IdentifierTree identifier ->
					nonNegative.contains(unit.element(identifier)) || unit.isNonNegativeField(unit.element(identifier));
			case MemberSelectTree select -> (select.getIdentifier().contentEquals("length")
					&& unit.type(select.getExpression()).getKind() == TypeKind.ARRAY)
					|| unit.isNonNegativeField(unit.element(select));
			case MethodInvocationTree call -> isSizeOrAbs(call) || unit.returnsNonNegative(unit.element(call));
			case ParenthesizedTree parenthesized -> knownNonNegative(parenthesized.getExpression());
			case BinaryTree binary -> switch (binary.getKind()) {
				case PLUS, MULTIPLY, DIVIDE, REMAINDER ->
						knownNonNegative(binary.getLeftOperand()) && knownNonNegative(binary.getRightOperand());
				default -> false;
			};
			default -> false;
		};
	}

	private boolean isSizeOrAbs(MethodInvocationTree call) {
		ExecutableElement method = (ExecutableElement) unit.element(call);
		String name = method.getSimpleName().toString();
		TypeElement owner = JavaTypes.owner(method);
		String ownerName = owner.getQualifiedName().toString();
		return (name.equals("size") && call.getArguments().isEmpty()
				&& (types().isSubtype(owner.asType(), "java.util.Collection")
						|| types().isSubtype(owner.asType(), "java.util.Map")))
				|| (name.equals("length") && ownerName.equals("java.lang.String"))
				|| (name.equals("abs") && ownerName.equals("java.lang.Math"));
	}

	boolean refersTo(ExpressionTree tree, Element element) {
		return tree instanceof IdentifierTree identifier && element.equals(unit.element(identifier));
	}

	/**
	 * Whether every write to the variable in the scope keeps a non-negative value non-negative: {@code ++}; {@code +=},
	 * {@code *=}, {@code /=} or {@code %=} with a non-negative value; or {@code =} of one.
	 */
	boolean onlyRaises(Tree scope, Element variable) {
		Boolean lowers = new TreeScanner<Boolean, Void>() {
			@Override
			public Boolean visitAssignment(AssignmentTree node, Void unused) {
				return (writes(node.getVariable(), variable) && !knownNonNegative(node.getExpression()))
						|| Boolean.TRUE.equals(super.visitAssignment(node, null));
			}

			@Override
			public Boolean visitCompoundAssignment(CompoundAssignmentTree node, Void unused) {
				return (writes(node.getVariable(), variable)
						&& !(RAISING.contains(node.getKind()) && knownNonNegative(node.getExpression())))
						|| Boolean.TRUE.equals(super.visitCompoundAssignment(node, null));
			}

			@Override
			public Boolean visitUnary(UnaryTree node, Void unused) {
				return (writes(node.getExpression(), variable) && (node.getKind() == Tree.Kind.PREFIX_DECREMENT
						|| node.getKind() == Tree.Kind.POSTFIX_DECREMENT))
						|| Boolean.TRUE.equals(super.visitUnary(node, null));
			}

			@Override
			public Boolean reduce(Boolean first, Boolean second) {
				return Boolean.TRUE.equals(first) || Boolean.TRUE.equals(second);
			}
		}.scan(scope, null);
		return !Boolean.TRUE.equals(lowers);
	}

	/** Whether the tree names the variable, as {@code count} or {@code this.count}. */
	private boolean writes(ExpressionTree tree, Element variable) {
		return (tree instanceof IdentifierTree || tree instanceof MemberSelectTree) && variable.equals(unit.element(tree));
	}

	/** Whether the body assigns the variable or changes it with ++, --, or a compound assignment. */
	private boolean assigns(Tree body, Element variable) {
		Boolean found = new TreeScanner<Boolean, Void>() {
			@Override
			public Boolean visitAssignment(AssignmentTree node, Void unused) {
				return refersTo(node.getVariable(), variable) || Boolean.TRUE.equals(super.visitAssignment(node, null));
			}

			@Override
			public Boolean visitCompoundAssignment(CompoundAssignmentTree node, Void unused) {
				return refersTo(node.getVariable(), variable)
						|| Boolean.TRUE.equals(super.visitCompoundAssignment(node, null));
			}

			@Override
			public Boolean visitUnary(UnaryTree node, Void unused) {
				return (isStep(node.getKind()) && refersTo(node.getExpression(), variable))
						|| Boolean.TRUE.equals(super.visitUnary(node, null));
			}

			@Override
			public Boolean reduce(Boolean first, Boolean second) {
				return Boolean.TRUE.equals(first) || Boolean.TRUE.equals(second);
			}
		}.scan(body, null);
		return Boolean.TRUE.equals(found);
	}

	/**
	 * Whether a loop bound keeps its value while the body runs, as a Lua numeric for assumes when it evaluates the
	 * bound once: literals, variables the body does not assign, and the size or length of a collection the body does
	 * not add to or remove from.
	 */
	private boolean unchangedBy(ExpressionTree bound, StatementTree body) {
		return switch (bound) {
			case LiteralTree _ -> true;
			case IdentifierTree identifier -> !assigns(body, unit.element(identifier));
			case ParenthesizedTree parenthesized -> unchangedBy(parenthesized.getExpression(), body);
			case BinaryTree binary -> unchangedBy(binary.getLeftOperand(), body) && unchangedBy(binary.getRightOperand(), body);
			case MemberSelectTree select -> select.getExpression() instanceof IdentifierTree array
					&& select.getIdentifier().contentEquals("length") && !assigns(body, unit.element(array));
			case MethodInvocationTree call -> call.getArguments().isEmpty()
					&& call.getMethodSelect() instanceof MemberSelectTree select
					&& (select.getIdentifier().contentEquals("size") || select.getIdentifier().contentEquals("length"))
					&& select.getExpression() instanceof IdentifierTree receiver
					&& !assigns(body, unit.element(receiver))
					&& !resizes(body, unit.element(receiver));
			default -> false;
		};
	}

	/** Whether the body calls a method that changes the size of the collection. */
	private boolean resizes(Tree body, Element collection) {
		Set<String> resizing = Set.of("add", "addAll", "remove", "removeAll", "removeIf", "retainAll", "clear", "put",
				"putAll", "append");
		Boolean found = new TreeScanner<Boolean, Void>() {
			@Override
			public Boolean visitMethodInvocation(MethodInvocationTree node, Void unused) {
				boolean here = node.getMethodSelect() instanceof MemberSelectTree select
						&& resizing.contains(select.getIdentifier().toString())
						&& refersTo(select.getExpression(), collection);
				return here || Boolean.TRUE.equals(super.visitMethodInvocation(node, null));
			}

			@Override
			public Boolean reduce(Boolean first, Boolean second) {
				return Boolean.TRUE.equals(first) || Boolean.TRUE.equals(second);
			}
		}.scan(body, null);
		return Boolean.TRUE.equals(found);
	}

	private static boolean containsCall(Tree tree) {
		Boolean found = new TreeScanner<Boolean, Void>() {
			@Override
			public Boolean visitMethodInvocation(MethodInvocationTree node, Void unused) {
				return true;
			}

			@Override
			public Boolean visitNewClass(NewClassTree node, Void unused) {
				return true;
			}

			@Override
			public Boolean reduce(Boolean first, Boolean second) {
				return Boolean.TRUE.equals(first) || Boolean.TRUE.equals(second);
			}
		}.scan(tree, null);
		return Boolean.TRUE.equals(found);
	}

	// ---- Index arithmetic

	static Expression plusOne(Expression expression) {
		return offset(expression, 1);
	}

	static Expression minusOne(Expression expression) {
		return offset(expression, -1);
	}

	/**
	 * {@code expression + delta}, folded where the result reads better: {@code 0 + 1} is {@code 1},
	 * {@code n - 1 + 1} is {@code n}, and {@code n + 2 - 1} is {@code n + 1}.
	 */
	private static Expression offset(Expression expression, long delta) {
		Long literal = integerValue(expression);
		if (literal != null) {
			return integer(literal + delta);
		}
		if (expression instanceof Expression.Binary sum
				&& (sum.operator() == BinaryOperator.ADD || sum.operator() == BinaryOperator.SUBTRACT)) {
			Long amount = integerValue(sum.right());
			if (amount != null) {
				long combined = (sum.operator() == BinaryOperator.ADD ? amount : -amount) + delta;
				if (combined == 0) {
					return sum.left();
				}
				return combined > 0
						? binary(sum.left(), BinaryOperator.ADD, integer(combined))
						: binary(sum.left(), BinaryOperator.SUBTRACT, integer(-combined));
			}
		}
		return delta > 0
				? binary(expression, BinaryOperator.ADD, integer(delta))
				: binary(expression, BinaryOperator.SUBTRACT, integer(-delta));
	}

	/** The value of a decimal integer numeral, or of a negated one; null for anything else. */
	static @Nullable Long integerValue(Expression expression) {
		if (expression instanceof Expression.NumberLiteral numeral && numeral.text().matches("\\d+")) {
			return Long.parseLong(numeral.text());
		}
		if (expression instanceof Expression.Unary negation && negation.operator() == UnaryOperator.NEGATE
				&& negation.operand() instanceof Expression.NumberLiteral numeral && numeral.text().matches("\\d+")) {
			return -Long.parseLong(numeral.text());
		}
		return null;
	}
}
