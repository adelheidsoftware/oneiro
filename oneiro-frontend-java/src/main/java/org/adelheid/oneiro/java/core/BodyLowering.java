package org.adelheid.oneiro.java.core;

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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
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
import org.adelheid.oneiro.java.language.CharConversions;
import org.adelheid.oneiro.java.language.Exceptions;
import org.adelheid.oneiro.java.language.Lambdas;
import org.adelheid.oneiro.java.language.Loops;
import org.adelheid.oneiro.java.language.MethodReferences;
import org.adelheid.oneiro.java.language.ObjectCreation;
import org.adelheid.oneiro.java.language.Operators;
import org.adelheid.oneiro.java.language.Switches;
import org.adelheid.oneiro.java.language.TypeTests;
import org.adelheid.oneiro.java.library.Comparators;
import org.adelheid.oneiro.java.library.LibraryCalls;
import org.adelheid.oneiro.java.library.StreamLoops;
import org.adelheid.oneiro.java.library.StringBuilding;
import org.adelheid.oneiro.java.plugin.ApiCalls;
import org.adelheid.oneiro.lowering.LocalNames;
import org.adelheid.oneiro.lowering.TableFolding;
import org.adelheid.oneiro.lowering.translation.Helper;
import org.adelheid.oneiro.lowering.translation.PlainValues;
import org.adelheid.oneiro.lua.BinaryOperator;
import org.adelheid.oneiro.lua.Block;
import org.adelheid.oneiro.lua.Comment;
import org.adelheid.oneiro.lua.Expression;
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
public final class BodyLowering {

	private static final Expression SELF = name("self");

	/** What to write instead of a value whose code cannot run before the statement it is in. */
	private static final String LOCAL_FIRST = "give its value to a local of its own first, var x = ...";
	/** Why the Lua of an assignment that reads its target works the target's parts out twice, for a message. */
	private static final String READ_TARGET = "Lua works this value out twice, to read the target and to assign it";
	/** The longest call a message shows as written. */
	private static final int SHOWN_CALL = 40;
	/** The name javac gives a parameter of a compiled class that keeps no names. */
	private static final Pattern UNNAMED_PARAMETER = Pattern.compile("(arg|p)\\d+");
	/** The names of made-up locals that hold a list, a set or a map; see {@link #collectionName}. */
	public static final Set<String> COLLECTIONS = Set.of("list", "set", "map");

	public final UnitLowering unit;
	public final boolean hasSelf;
	public final boolean inConstructor;
	// Whether self is an anonymous class's object, whose methods cannot read the outer object's members
	public boolean anonymousObject;

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
	public final Set<Element> nonNegative = new HashSet<>();
	// Map.Entry loop variables and the Lua names of their key and value, for for (var e : map.entrySet())
	public final Map<Element, List<String>> entries = new HashMap<>();
	// Locals holding a Lua function's several return values, and the Lua local of each record component by accessor
	private final Map<Element, Map<String, String>> returnValues = new HashMap<>();
	// The parameters of the catch clauses being lowered, which stand for the error value pcall hands back
	public final Set<Element> caughtErrors = new HashSet<>();
	// The locals made up for this code that live on after their statement, which no local made up later takes; see
	// invent
	private final Set<String> invented = new HashSet<>();
	// Variables that stand for a value of the code around them: a stream lambda's parameter for the loop's element, a
	// pattern's variable for the value it tests
	private final Map<Element, Expression> aliases = new HashMap<>();
	// The statement being lowered, and the code that values inside it need run before it; see hoisted
	private @Nullable Hoisting hoisting;
	// What expressions read more than once lower to, once lowered; see reused
	private final Map<ExpressionTree, Expression> reusedValues = new HashMap<>();

	/**
	 * @param hasSelf whether the code runs with an instance, so that {@code this} is {@code self}
	 * @param inConstructor whether {@code return;} must return {@code self}, as at the end of {@code new}
	 */
	public BodyLowering(UnitLowering unit, boolean hasSelf, boolean inConstructor) {
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

	public ApiCalls api() {
		return api;
	}

	public StringBuilding strings() {
		return strings;
	}

	public LibraryCalls library() {
		return library;
	}

	public Comparators comparators() {
		return comparators;
	}

	public MethodReferences methodReferences() {
		return methodReferences;
	}

	public SourceFacts facts() {
		return facts;
	}

	public Lambdas lambdas() {
		return lambdas;
	}

	Loops loops() {
		return loops;
	}

	public Exceptions exceptions() {
		return exceptions;
	}

	public TypeTests typeTests() {
		return typeTests;
	}

	public Operators operators() {
		return operators;
	}

	public JavaTypes types() {
		return unit.types;
	}

	// ---- Blocks and comments

	public Block block(BlockTree block) {
		return statements(block.getStatements(), unit.start(block) + 1, unit.end(block) - 1);
	}

	/** A loop or branch body: a block, or a single statement standing in for one. */
	public Block body(StatementTree body) {
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
	public Block statements(List<? extends StatementTree> statements, int from, int to) {
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
				parts = hoistingInto(statement, () -> entries(statement));
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
		return new Block(TableFolding.fold(lowered), pending);
	}

	public static List<Comment> comments(List<CommentIndex.SourceComment> sourceComments) {
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

	public List<Statement> statement(StatementTree statement) {
		return switch (statement) {
			case VariableTree variable -> localVariable(variable);
			case ExpressionStatementTree expression -> expressionStatement(expression.getExpression());
			case IfTree branch -> List.of(ifChain(branch));
			case WhileLoopTree loop ->
					List.of(new Statement.While(condition(loop.getCondition()),
							loops.loopBody(loop.getStatement(), List.of())));
			case DoWhileLoopTree loop -> List.of(new Statement.Repeat(
					loops.loopBody(loop.getStatement(), List.of()),
					new Expression.Unary(UnaryOperator.NOT, condition(loop.getCondition()))));
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

	public static Rejection labelledJump(Tree tree) {
		return new Rejection(tree, "Lua's break and continue only reach the innermost loop; move the loops into a"
				+ " method and return from it.");
	}

	public List<Statement> localVariable(VariableTree variable) {
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
		if (isFree(preferred, variable, Set.of())) {
			return invent(preferred, variable);
		}
		return invent(variable.getName() + preferred.substring(0, 1).toUpperCase(Locale.ROOT) + preferred.substring(1),
				variable);
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
	public void alias(VariableTree parameter, String luaName) {
		aliases.put(unit.element(parameter), name(luaName));
	}

	/** Makes a declared variable stand for a value, as a pattern's variable stands for the value it tests. */
	public void alias(VariableTree variable, Expression value) {
		aliases.put(unit.element(variable), value);
	}

	/** The Lua name for a declared local or parameter, after checking that Lua can hold it. */
	public String declare(VariableTree variable) {
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
	public List<Statement> expressionStatement(ExpressionTree expression) {
		return switch (expression) {
			case AssignmentTree assignment -> {
				Expression target = assignable(assignment.getVariable());
				yield deliver(assignment.getExpression(), value -> Statement.assignment(target, value));
			}
			case CompoundAssignmentTree compound -> List.of(compoundAssignment(compound));
			case UnaryTree unary when isStep(unary.getKind()) -> {
				Expression target = readTarget(unary.getExpression());
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
	 * The target of an assignment that reads it as well, {@code target = target + value}, with the parts Lua would work
	 * out twice, such as a call's value or a position, read once, into locals before the statement; see
	 * {@link #reused}.
	 */
	private Expression readTarget(ExpressionTree targetTree) {
		Expression target = assignable(targetTree);
		ExpressionTree written = withoutParentheses(targetTree);
		if (target instanceof Expression.Index index && written instanceof ArrayAccessTree access) {
			ExpressionTree holder = access.getExpression();
			// A position of an array is one higher in Lua, so its local is the position Lua reads
			return new Expression.Index(reused(index.target(), holder, READ_TARGET, valueName(holder)),
					reused(index.key(), access.getIndex(), READ_TARGET, "index"));
		}
		if (target instanceof Expression.Field field && written instanceof MemberSelectTree select) {
			ExpressionTree holder = select.getExpression();
			return new Expression.Field(reused(field.target(), holder, READ_TARGET, valueName(holder)), field.name());
		}
		return target;
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
		Expression target = readTarget(targetTree);
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
			branches.add(new Statement.If.Branch(condition(current.getCondition()), body(current.getThenStatement())));
			StatementTree orElse = current.getElseStatement();
			if (orElse instanceof IfTree next) {
				current = next;
			} else {
				return new Statement.If(branches, orElse == null ? null : body(orElse));
			}
		}
	}

	/**
	 * An expression that decides a branch or a loop. An optional dependency's {@code isLoaded()} is its global itself
	 * there, which Lua tests for nil as it tests the condition, {@code if gauges then}, as it is in its negation and
	 * beside another condition; elsewhere the check is a boolean, {@code gauges ~= nil}.
	 */
	public Expression condition(ExpressionTree condition) {
		ExpressionTree inner = withoutParentheses(condition);
		String alias = unit.optionalChecks().loaded(inner);
		if (alias != null) {
			unit.useGlobal(alias);
			return name(alias);
		}
		if (inner instanceof UnaryTree not && not.getKind() == Tree.Kind.LOGICAL_COMPLEMENT
				&& unit.optionalChecks().loaded(not.getExpression()) != null) {
			return new Expression.Unary(UnaryOperator.NOT, condition(not.getExpression()));
		}
		boolean andOr = inner.getKind() == Tree.Kind.CONDITIONAL_AND || inner.getKind() == Tree.Kind.CONDITIONAL_OR;
		if (andOr && inner instanceof BinaryTree both
				&& (isLoadedCheck(both.getLeftOperand()) || isLoadedCheck(both.getRightOperand()))) {
			return Expression.binary(condition(both.getLeftOperand()),
					inner.getKind() == Tree.Kind.CONDITIONAL_AND ? BinaryOperator.AND : BinaryOperator.OR,
					condition(both.getRightOperand()));
		}
		return expression(condition);
	}

	/** Whether an expression is an optional dependency's check, or its negation. */
	private boolean isLoadedCheck(ExpressionTree expression) {
		ExpressionTree inner = withoutParentheses(expression);
		return unit.optionalChecks().loaded(inner) != null || (inner instanceof UnaryTree not
				&& not.getKind() == Tree.Kind.LOGICAL_COMPLEMENT && unit.optionalChecks().loaded(not.getExpression())
						!= null);
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
	 * a conditional or a switch that Lua has no expression for, with the sink in every branch, after the code the
	 * branch's values need.
	 */
	public List<Statement> deliver(ExpressionTree value, Function<Expression, Statement> sink) {
		ExpressionTree inner = withoutParentheses(value);
		if (inner instanceof ConditionalExpressionTree conditional && !operators.safeAsAndOr(conditional)) {
			List<Statement.If.Branch> branches = new ArrayList<>();
			ExpressionTree whenTrue = conditional.getTrueExpression();
			branches.add(new Statement.If.Branch(condition(conditional.getCondition()),
					blockOf(statementsOf(whenTrue, () -> deliver(whenTrue, sink)))));
			ExpressionTree otherwise = conditional.getFalseExpression();
			List<Statement> loweredOtherwise = statementsOf(otherwise, () -> deliver(otherwise, sink));
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
	public List<Statement> returned(ExpressionTree value) {
		StreamLoops.Pipeline pipeline = streams.pipeline(value);
		if (pipeline != null) {
			return streams.lower(pipeline, new StreamLoops.Destination.Returned());
		}
		return deliver(value, lowered -> new Statement.Return(List.of(lowered)));
	}

	public static Block blockOf(List<Statement> statements) {
		return Block.of(statements.toArray(Statement[]::new));
	}

	public static ExpressionTree withoutParentheses(ExpressionTree tree) {
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
	public boolean uses(Tree tree, Element element) {
		return new TreeSearch() {
			@Override
			public Boolean visitIdentifier(IdentifierTree node, Void unused) {
				return facts.refersTo(node, element);
			}
		}.foundIn(tree);
	}

	/**
	 * A name for a local the Lua needs that the Java does not have, declared among the statements of a block, such as
	 * {@code pcall}'s {@code ok}: the preferred one, unless the method or the file has it, or another local made up in
	 * this code, then the preferred one with a number, from 2; see {@link UnitLowering#freshLocal}. No local made up
	 * later in this code takes it, so none hides it, in the block or inside it.
	 */
	public String invent(String preferred, Tree where) {
		return invent(preferred, where, Set.of());
	}

	/** A name as {@link #invent(String, Tree)} makes one, which takes none of the others either. */
	public String invent(String preferred, Tree where, Set<String> others) {
		String name = inventInner(preferred, where, others);
		invented.add(name);
		return name;
	}

	/**
	 * A name for a variable of a loop or a parameter of a function the Lua makes, which lives only inside it: made as
	 * {@link #invent} makes one, but free again after it, as Lua's loops one after another each name their key
	 * {@code key}.
	 */
	public String inventInner(String preferred, Tree where) {
		return inventInner(preferred, where, Set.of());
	}

	/** A name as {@link #inventInner(String, Tree)} makes one, which takes none of the others either. */
	public String inventInner(String preferred, Tree where, Set<String> others) {
		Set<String> taken = new HashSet<>(invented);
		taken.addAll(others);
		return unit.freshLocal(preferred, where, taken);
	}

	/** Whether a made-up local could take a name as it is, without a number; see {@link #invent}. */
	public boolean isFree(String name, Tree where, Set<String> others) {
		return inventInner(name, where, others).equals(unit.names.luaName(name));
	}

	/**
	 * The name of a list, a set or a map that a made-up local holds: the name of where it goes where that is free, the
	 * parameter it is handed to or the field it becomes, {@code texts} or {@code lines}; otherwise the kind of value it
	 * is, {@code list}, with a number where that is taken.
	 *
	 * @param destination the name of where the value goes, or null where it goes to no name, as a return
	 */
	public String collectionName(String kind, @Nullable String destination, Tree where, Set<String> others) {
		if (destination != null && isFree(destination, where, others)) {
			return invent(destination, where, others);
		}
		return invent(kind, where, others);
	}

	/**
	 * The name of the field a sink assigns its value to, {@code lines} for {@code self.lines = value} and for
	 * {@code self.lines[section] = value}; null for a sink that returns the value or assigns it to a local.
	 */
	public static @Nullable String destinationName(Function<Expression, Statement> sink) {
		// Sinks only make the statement, so making one to look at changes nothing
		if (!(sink.apply(new Expression.Nil()) instanceof Statement.Assignment assignment)
				|| assignment.targets().size() != 1) {
			return null;
		}
		return switch (assignment.targets().getFirst()) {
			case Expression.Field field -> field.name();
			case Expression.Index index when index.target() instanceof Expression.Field field -> field.name();
			default -> null;
		};
	}

	// ---- Expressions

	public Expression expression(ExpressionTree tree) {
		Expression reused = reusedValues.get(tree);
		if (reused != null) {
			return reused;
		}
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
	public Expression asWritten(ExpressionTree tree) {
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
			case SwitchExpressionTree switched -> hoistedValue(switched);
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

	public Expression self(Tree where) {
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
	public Expression receiverFor(TypeElement owner, Tree where) {
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
		StreamLoops.Pipeline pipeline = streams.pipeline(call);
		if (pipeline != null) {
			return hoistedStream(call, pipeline);
		}
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
	public List<Expression> arguments(ExecutableElement method, List<? extends ExpressionTree> arguments) {
		if (method.isVarArgs() && arguments.size() == method.getParameters().size()
				&& unit.type(arguments.getLast()).getKind() == TypeKind.ARRAY) {
			List<Expression> lowered = new ArrayList<>(arguments(arguments.subList(0, arguments.size() - 1)));
			lowered.add(call(Expression.field(name("table"), "unpack"), expression(arguments.getLast())));
			return lowered;
		}
		return arguments(arguments);
	}

	public List<Expression> arguments(List<? extends ExpressionTree> arguments) {
		return arguments.stream().map(this::expression).toList();
	}

	// ---- Code that values need before their statement

	/**
	 * The statement being lowered, the code that values inside it need run before it, such as a conditional's or a
	 * stream's, and those values; see {@link #hoisted}.
	 */
	private static final class Hoisting {

		final Tree root;
		final List<Statement> statements = new ArrayList<>();
		final List<Tree> values = new ArrayList<>();

		Hoisting(Tree root) {
			this.root = root;
		}
	}

	/** Lowers a statement, after the code that values inside it need run before it; see {@link #hoisted}. */
	private List<Block.Entry> hoistingInto(Tree statement, Supplier<List<Block.Entry>> lower) {
		Hoisting inner = new Hoisting(statement);
		List<Block.Entry> lowered = within(inner, lower);
		List<Block.Entry> entries = new ArrayList<>(inner.statements.stream().map(Block.Entry::of).toList());
		entries.addAll(lowered);
		return entries;
	}

	/**
	 * Lowers code that runs on its own, such as a lambda's expression or a branch's value, after the code that values
	 * inside it need run before it; see {@link #hoisted}.
	 */
	public List<Statement> statementsOf(Tree code, Supplier<List<Statement>> lower) {
		Hoisting inner = new Hoisting(code);
		List<Statement> lowered = within(inner, lower);
		List<Statement> statements = new ArrayList<>(inner.statements);
		statements.addAll(lowered);
		return statements;
	}

	/** An expression's value, read as code on its own would read it, after the code its values need, into code. */
	public Expression valueOf(ExpressionTree value, List<Statement> code) {
		Hoisting inner = new Hoisting(value);
		Expression lowered = within(inner, () -> expression(value));
		code.addAll(inner.statements);
		return lowered;
	}

	/** Lowers code whose hoisted values go before the statement {@code inner} stands for. */
	private <T> T within(Hoisting inner, Supplier<T> lower) {
		Hoisting outer = hoisting;
		hoisting = inner;
		try {
			return lower.get();
		} finally {
			hoisting = outer;
		}
	}

	/**
	 * A conditional or a switch inside an expression, whose branches Lua cannot say as one: a local before the
	 * statement takes its value, which each branch assigns, {@code local text} and
	 * {@code if ... then text = ... end}; see {@link #hoisted}.
	 */
	public Expression hoistedValue(ExpressionTree value) {
		String kind = withoutParentheses(value) instanceof SwitchExpressionTree ? "switch" : "conditional";
		return hoistedInto(value, hoistedName(value), "In Lua this " + kind + " is a statement", LOCAL_FIRST,
				name -> {
					List<Statement> statements = new ArrayList<>();
					statements.add(new Statement.Local(List.of(name), List.of()));
					statements.addAll(deliver(value, lowered -> Statement.assignment(name(name), lowered)));
					return statements;
				});
	}

	/**
	 * A stream inside an expression, {@code (int) names.stream().filter(...).count()}: as for a conditional there, a
	 * local before the statement takes its value, named after its result, or a list after the parameter it is handed
	 * to; see {@link #hoisted}.
	 */
	private Expression hoistedStream(ExpressionTree value, StreamLoops.Pipeline pipeline) {
		String kind = streams.resultName(pipeline);
		String destination = COLLECTIONS.contains(kind) ? parameterName(value, true) : null;
		String preferred = destination != null && isFree(destination, value, Set.of()) ? destination : kind;
		return hoistedInto(value, preferred, "In Lua a stream is a loop", LOCAL_FIRST,
				name -> streams.lower(pipeline, new StreamLoops.Destination.Local(name)));
	}

	/**
	 * A value inside an expression that Lua makes with statements: the statements {@code lower} makes, which give the
	 * value to a local of the name it is passed, run before the statement, and the expression reads the local; see
	 * {@link #hoisted}.
	 *
	 * @param what what Lua makes of the value, for the message where it cannot run before the statement
	 * @param instead what to write instead, for that message
	 */
	private Expression hoistedInto(ExpressionTree value, String preferred, String what, String instead,
			Function<String, List<Statement>> lower) {
		Hoisting around = hoisted(value, what, instead, value);
		String name = invent(preferred, value);
		around.statements.addAll(lower.apply(name));
		around.values.add(value);
		return name(name);
	}

	/**
	 * The statement that the code a value inside it needs can run before, where nothing the statement does would see
	 * the difference; otherwise rejects the value, saying why not.
	 *
	 * @param what what Lua makes of the value, for the message where it cannot run before the statement
	 * @param instead what to write instead, for that message
	 * @param at where that message points
	 */
	private Hoisting hoisted(Tree value, String what, String instead, Tree at) {
		Hoisting around = hoisting;
		String why = whyNotHoisted(value, around);
		if (why != null || around == null) {
			throw new Rejection(at, what + ", which can run before the code around it only where nothing would see"
					+ " the difference, but " + why + "; " + instead);
		}
		return around;
	}

	/**
	 * A value the Lua reads more than once, lowered once: as it is when reading it again gives the same (see
	 * {@link PlainValues}), and otherwise in a local before the statement, which each read names, after the value (see
	 * {@link #valueName}). Lowering the expression again gives the same.
	 *
	 * @param what why the Lua reads the value more than once, for the message where it cannot go in a local first
	 */
	public Expression reused(ExpressionTree value, String what) {
		Expression known = reusedValues.get(value);
		if (known != null) {
			return known;
		}
		Expression read = reused(expression(value), value, what, valueName(value));
		reusedValues.put(value, read);
		return read;
	}

	/** A value the Lua reads more than once, already lowered from {@code where}: as {@link #reused} reads one. */
	public Expression reused(Expression lowered, ExpressionTree where, String what, String preferred) {
		if (PlainValues.isPlain(lowered)) {
			return lowered;
		}
		Hoisting around = hoisted(where, what + ", so it goes into a local first", "store it in a local first.", where);
		String name = invent(preferred, where);
		around.statements.add(new Statement.Local(List.of(name), List.of(lowered)));
		around.values.add(where);
		return name(name);
	}

	/**
	 * {@code a && b} and {@code a || b}. When Lua can say the right side only with statements before it, such as a
	 * stream's loop, the whole test goes into a local before the statement, {@code local both = a}, and the right
	 * side's statements run inside {@code if both then}, which ends {@code both = b}, so that they run only when Java's
	 * right side would; {@code either} and {@code if not either then} for {@code ||}. That local is a hoisted value's;
	 * see {@link #hoisted}.
	 */
	public Expression shortCircuit(BinaryTree binary) {
		boolean and = binary.getKind() == Tree.Kind.CONDITIONAL_AND;
		BinaryOperator operator = and ? BinaryOperator.AND : BinaryOperator.OR;
		Expression left = expression(binary.getLeftOperand());
		ExpressionTree rightTree = binary.getRightOperand();
		Set<Element> aliased = new HashSet<>(aliases.keySet());
		List<Statement> rightCode = new ArrayList<>();
		Expression right = valueOf(rightTree, rightCode);
		if (rightCode.isEmpty()) {
			return binary(left, operator, right);
		}
		String shown = and ? "&&" : "||";
		// A pattern's variable that stands for a local of the right side's code would outlive that local
		Set<String> declared = new HashSet<>();
		rightCode.stream()
				.filter(Statement.Local.class::isInstance)
				.forEach(local -> declared.addAll(((Statement.Local) local).names()));
		if (aliases.entrySet().stream().anyMatch(alias -> !aliased.contains(alias.getKey())
				&& alias.getValue() instanceof Expression.Name local && declared.contains(local.name()))) {
			throw new Rejection(rightTree, "The pattern's variable would stand for a local that lives only while Lua"
					+ " works out the right side of this " + shown + "; test the value in an if of its own first.");
		}
		Hoisting around = hoisted(binary, "In Lua the right side of this " + shown + " runs as statements",
				"give the test to a local of its own first, var x = ...", rightTree);
		String name = invent(and ? "both" : "either", binary);
		Expression local = name(name);
		rightCode.add(Statement.assignment(local, right));
		Expression undecided = and ? local : new Expression.Unary(UnaryOperator.NOT, local);
		around.statements.add(new Statement.Local(List.of(name), List.of(left)));
		around.statements.add(Statement.ifThen(undecided, blockOf(rightCode)));
		around.values.add(binary);
		return local;
	}

	/**
	 * Why the code a value inside a statement needs cannot run before the statement, or null when it can: the
	 * statement must run the value each time it runs, not only when an {@code &&}, an {@code ||}, a conditional or a
	 * switch gets to it, nor each turn of a loop or each time a lambda runs; and nothing the statement evaluates before
	 * the value may call a method, whose effects would then come after the value's code. The values already hoisted
	 * run before the statement too, and their calls with them; a stream's steps, and the code of lambdas, run no
	 * method where they are written.
	 */
	private @Nullable String whyNotHoisted(Tree value, @Nullable Hoisting around) {
		String outside = "here there is no statement for it to go before";
		if (around == null) {
			return outside;
		}
		TreePath path = unit.path(value);
		while (!path.getLeaf().equals(around.root)) {
			TreePath parentPath = path.getParentPath();
			if (parentPath == null) {
				return outside;
			}
			Tree child = path.getLeaf();
			String why = switch (parentPath.getLeaf()) {
				case LambdaExpressionTree _ -> "it is inside a lambda";
				case BinaryTree binary when binary.getKind() == Tree.Kind.CONDITIONAL_AND
						&& child.equals(binary.getRightOperand()) -> "it runs only when the && gets to it";
				case BinaryTree binary when binary.getKind() == Tree.Kind.CONDITIONAL_OR
						&& child.equals(binary.getRightOperand()) -> "it runs only when the || gets to it";
				case ConditionalExpressionTree conditional when !child.equals(conditional.getCondition()) ->
						"it runs only when the conditional takes its branch";
				case SwitchExpressionTree switched when !child.equals(switched.getExpression()) ->
						"it runs only when the switch takes its case";
				case WhileLoopTree loop when child.equals(loop.getCondition()) ->
						"it runs each time the loop tests its condition";
				case DoWhileLoopTree loop when child.equals(loop.getCondition()) ->
						"it runs each time the loop tests its condition";
				case ForLoopTree loop when child.equals(loop.getCondition()) || loop.getUpdate().contains(child) ->
						"it runs each time round the loop";
				default -> null;
			};
			if (why != null) {
				return why;
			}
			path = parentPath;
		}
		int start = unit.start(value);
		List<Tree> calls = new ArrayList<>();
		new TreeScanner<Void, Void>() {
			@Override
			public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
				// A stream's steps are no calls in Lua, which reads only what they are given
				if (!types().isStream(unit.type(node))) {
					calls.add(node);
				}
				return super.visitMethodInvocation(node, null);
			}

			@Override
			public Void visitNewClass(NewClassTree node, Void unused) {
				calls.add(node);
				return super.visitNewClass(node, null);
			}

			// The calls of lambdas and classes run when they are called, not where they are written
			@Override
			public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
				return null;
			}

			@Override
			public Void visitClass(ClassTree node, Void unused) {
				return null;
			}
		}.scan(around.root, null);
		for (Tree call : calls) {
			if (unit.end(call) <= start && around.values.stream().noneMatch(hoisted -> contains(hoisted, call))) {
				return "it comes after " + shown(call) + ", which would then run later";
			}
		}
		return null;
	}

	/** Whether one tree is inside another, or is the other. */
	private boolean contains(Tree outer, Tree inner) {
		return unit.start(outer) <= unit.start(inner) && unit.end(inner) <= unit.end(outer);
	}

	/** A call as a message names it: as written, with its receiver, where that is short, and otherwise its name. */
	private String shown(Tree call) {
		String text = unit.sourceText(call);
		if (text.length() <= SHOWN_CALL && text.indexOf('\n') < 0) {
			return text;
		}
		return switch (call) {
			case MethodInvocationTree invocation -> methodName(invocation) + "(...)";
			case NewClassTree creation -> "new " + creation.getIdentifier() + "(...)";
			default -> text;
		};
	}

	private static String methodName(MethodInvocationTree call) {
		return switch (call.getMethodSelect()) {
			case MemberSelectTree select -> select.getIdentifier().toString();
			case IdentifierTree identifier -> identifier.getName().toString();
			default -> call.getMethodSelect().toString();
		};
	}

	/**
	 * The name of the local a value read more than once goes into: the field's or the method's that gives it, as
	 * written, {@code detailed} for {@code ledger.detailed()}, but without {@code get} or {@code is},
	 * {@code name} for {@code getName()}; otherwise as {@link #hoistedName} names it.
	 */
	public String valueName(ExpressionTree value) {
		ExpressionTree plain = withoutParentheses(value);
		while (plain instanceof TypeCastTree cast) {
			plain = withoutParentheses(cast.getExpression());
		}
		String own = switch (plain) {
			case MethodInvocationTree call -> withoutPrefix(methodName(call));
			case MemberSelectTree select -> select.getIdentifier().toString();
			case IdentifierTree identifier -> identifier.getName().toString();
			default -> null;
		};
		return own != null ? decapitalized(own) : hoistedName(value);
	}

	/** A method's name as its value's: without {@code get} or {@code is}, {@code Name} for {@code getName}. */
	private static String withoutPrefix(String name) {
		for (String prefix : List.of("get", "is")) {
			if (name.length() > prefix.length() && name.startsWith(prefix)
					&& Character.isUpperCase(name.charAt(prefix.length()))) {
				return name.substring(prefix.length());
			}
		}
		return name;
	}

	private static String decapitalized(String name) {
		return name.substring(0, 1).toLowerCase(Locale.ROOT) + name.substring(1);
	}

	/**
	 * The name of a hoisted value's local where the value has none of its own: the parameter's that it is passed to,
	 * or one from its type; see {@link #typeName}.
	 */
	private String hoistedName(ExpressionTree value) {
		String parameter = parameterName(value, false);
		return parameter != null ? parameter : typeName(unit.type(value));
	}

	/**
	 * The name of the parameter a value is passed to; null for a value that is no argument, a varargs' value, and one
	 * of a compiled class that keeps no names, which javac calls {@code arg0} and so on.
	 *
	 * @param ownOnly whether only a method of the plugin's or the client's names it, not one of the Java library,
	 *     whose names say only what it takes
	 */
	private @Nullable String parameterName(ExpressionTree value, boolean ownOnly) {
		TreePath parent = unit.path(value).getParentPath();
		List<? extends ExpressionTree> arguments = switch (parent == null ? null : parent.getLeaf()) {
			case MethodInvocationTree call -> call.getArguments();
			case NewClassTree creation -> creation.getArguments();
			case null, default -> null;
		};
		int index = arguments == null ? -1 : arguments.indexOf(value);
		if (index < 0 || !(unit.element(parent.getLeaf()) instanceof ExecutableElement method)) {
			return null;
		}
		List<? extends VariableElement> parameters = method.getParameters();
		boolean varargs = method.isVarArgs() && index >= parameters.size() - 1;
		TypeElement owner = JavaTypes.owner(method);
		boolean foreign = !types().isPluginType(owner) && !types().isApi(owner);
		if (index >= parameters.size() || varargs || (ownOnly && foreign)) {
			return null;
		}
		String name = parameters.get(index).getSimpleName().toString();
		return UNNAMED_PARAMETER.matcher(name).matches() ? null : name;
	}

	/**
	 * A name for a local from its value's type: {@code text} for a string, and for a class of the plugin or the
	 * client its name, {@code label} for a {@code Label}; {@code value} otherwise.
	 */
	private String typeName(TypeMirror type) {
		if (types().isString(type)) {
			return "text";
		}
		if (type instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element
				&& (types().isPluginType(element) || types().isApi(element))) {
			return decapitalized(element.getSimpleName().toString());
		}
		return "value";
	}

	/**
	 * Whether Lua makes a value inside a branch with statements: a stream, a switch, or a conditional it cannot say as
	 * one expression, whose statements cannot run before the code around them, as the branch runs only sometimes. The
	 * code of lambdas and classes inside the branch is left out, as it runs when they are called.
	 */
	public boolean needsStatementsWithin(ExpressionTree branch) {
		return new TreeSearch() {
			@Override
			public Boolean visitSwitchExpression(SwitchExpressionTree node, Void unused) {
				return true;
			}

			@Override
			public Boolean visitConditionalExpression(ConditionalExpressionTree node, Void unused) {
				return !operators.safeAsAndOr(node) || super.visitConditionalExpression(node, null);
			}

			@Override
			public Boolean visitMethodInvocation(MethodInvocationTree node, Void unused) {
				return streams.pipeline(node) != null || super.visitMethodInvocation(node, null);
			}

			@Override
			public Boolean visitLambdaExpression(LambdaExpressionTree node, Void unused) {
				return false;
			}

			@Override
			public Boolean visitClass(ClassTree node, Void unused) {
				return false;
			}
		}.foundIn(branch);
	}

	/**
	 * Takes over what the code around a lambda or anonymous class knows about its locals, which the inner code sees
	 * as upvalues.
	 */
	public void inherit(BodyLowering outer) {
		nonNegative.addAll(outer.nonNegative);
		entries.putAll(outer.entries);
		returnValues.putAll(outer.returnValues);
		caughtErrors.addAll(outer.caughtErrors);
		invented.addAll(outer.invented);
		aliases.putAll(outer.aliases);
	}
}
