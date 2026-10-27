package org.adelheid.oneiro.java.language;

import static org.adelheid.oneiro.lua.Expression.call;
import static org.adelheid.oneiro.lua.Expression.field;
import static org.adelheid.oneiro.lua.Expression.name;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import org.adelheid.oneiro.java.core.BodyLowering;
import org.adelheid.oneiro.java.core.JavaTypes;
import org.adelheid.oneiro.java.core.MemberComments;
import org.adelheid.oneiro.java.core.UnitLowering;
import org.adelheid.oneiro.lua.Block;
import org.adelheid.oneiro.lua.Comment;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.FunctionBody;
import org.adelheid.oneiro.lua.SourcePosition;
import org.adelheid.oneiro.lua.Statement;
import org.adelheid.oneiro.lua.Trivia;
import org.jspecify.annotations.Nullable;

/**
 * A class's constructor as its Lua {@code new} function, which also gives the instance fields their initial values and
 * runs the statements Java runs before {@code super(...)}.
 */
public final class Constructors {

	private final UnitLowering unit;

	public Constructors(UnitLowering unit) {
		this.unit = unit;
	}

	/**
	 * {@code Class.new}: a table with the class as its metatable (for a subclass, the superclass's object), the
	 * instance fields with their initializers or Java's defaults, then the constructor's body, and {@code self}
	 * returned.
	 */
	public UnitLowering.Member constructor(ClassTree type, TypeElement owner, String table,
			@Nullable MethodTree constructor, Trivia trivia, Overloading.@Nullable DefaultChain chain,
			List<Tree> fields, List<Trivia> fieldTrivia) {
		BodyLowering lowering = new BodyLowering(unit, true, true);
		List<String> parameters = new ArrayList<>();
		// An object enum's constant gets its name and ordinal first, which Java's Enum constructor takes
		boolean objectEnum = owner.getKind() == ElementKind.ENUM;
		String nameParameter = objectEnum ? lowering.invent("name", type) : "";
		String ordinalParameter = objectEnum ? lowering.invent("ordinal", type) : "";
		if (objectEnum) {
			parameters.add(nameParameter);
			parameters.add(ordinalParameter);
		}
		// An inner class's object gets its outer object first, and keeps it as self.outer
		boolean inner = JavaTypes.isInner(owner);
		String outerParameter = inner ? lowering.invent("outer", type) : "";
		if (inner) {
			parameters.add(outerParameter);
		}
		// A record's implicit or compact canonical constructor takes the components as parameters, and after its body
		// assigns each to the field its accessor reads
		List<String> components = new ArrayList<>();
		List<String> componentFields = new ArrayList<>();
		boolean record = owner.getKind() == ElementKind.RECORD;
		if (record && (constructor == null
				|| unit.elements.isCompactConstructor((ExecutableElement) unit.element(constructor)))) {
			List<? extends RecordComponentElement> recordComponents = owner.getRecordComponents();
			for (int index = 0; index < recordComponents.size(); index++) {
				RecordComponentElement component = recordComponents.get(index);
				componentFields.add(unit.types.memberName(component.getAccessor()));
				// The implicit constructor has no code of its own, which a parameter's name could clash with
				if (constructor == null) {
					components.add(unit.names.luaName(component.getSimpleName().toString()));
				} else {
					ExecutableElement compact = (ExecutableElement) unit.element(constructor);
					components.add(unit.codeLocalName(compact.getParameters().get(index), constructor));
				}
			}
			parameters.addAll(components);
		}
		boolean varargs = constructor != null && ((ExecutableElement) unit.element(constructor)).isVarArgs();
		List<Block.Entry> entries = new ArrayList<>();
		if (components.isEmpty() && constructor != null) {
			entries.addAll(unit.declareParameters(constructor.getParameters(), varargs, lowering, parameters));
		}
		entries.addAll(unit.overloading().defaultFills(constructor, chain, lowering));
		List<StatementTree> statements = constructor == null ? List.of() : ownStatements(constructor.getBody());
		List<Expression> superArguments = List.of();
		// The statements before super(...), which run before the object exists, as Java's do
		List<StatementTree> prologue = List.of();
		int prologueEnd = 0;
		for (int index = 0; index < statements.size(); index++) {
			MethodInvocationTree superCall = superCall(statements.get(index));
			if (superCall == null) {
				continue;
			}
			prologue = statements.subList(0, index);
			prologueEnd = unit.start(statements.get(index));
			superArguments = lowering.arguments(superCall.getArguments());
			statements = statements.subList(index + 1, statements.size());
			break;
		}
		// The comments after a block's last statement, such as an initializer block's, lead the statement after it
		List<Comment> pending = new ArrayList<>();
		if (constructor != null && !prologue.isEmpty()) {
			Block lowered = lowering.statements(prologue, unit.start(constructor.getBody()) + 1, prologueEnd);
			entries.addAll(lowered.entries());
			pending.addAll(lowered.closingComments());
		}
		TypeElement superclass = unit.pluginSuperclass(owner);
		Expression object = superclass == null
				? new Expression.Table(List.of())
				: new Expression.Call(field(unit.moduleReference(superclass), "new"), superArguments);
		add(entries, List.of(Block.Entry.of(new Statement.Local(List.of("self"),
				List.of(call(name("setmetatable"), object, name(table)))))), pending);
		if (inner) {
			entries.add(assignToSelf("outer", name(outerParameter)));
		}
		if (objectEnum) {
			entries.add(assignToSelf("name", name(nameParameter)));
			entries.add(assignToSelf("ordinal", name(ordinalParameter)));
		}
		// A record's components are assigned from the constructor's parameters, so they need no defaults
		for (int index = 0; index < fields.size() && !record; index++) {
			if (fields.get(index) instanceof BlockTree block) {
				// An instance initializer block runs here, among the field initializers. The comments above the
				// block come before those above its first statement.
				Block lowered = lowering.block(block);
				pending.addAll(fieldTrivia.get(index).leading());
				add(entries, initializerEntries(lowered, fieldTrivia.get(index)), pending);
				pending.addAll(lowered.closingComments());
				continue;
			}
			VariableTree field = (VariableTree) fields.get(index);
			lowering.declare(field);
			ExpressionTree initializer = field.getInitializer();
			Expression value = initializer == null
					? unit.defaultValue(unit.type(field)) : lowering.expression(initializer);
			if (value != null) {
				Statement assignment = Statement.assignment(field(name("self"), unit.luaMemberName(field)), value);
				add(entries, List.of(new Block.Entry(assignment, fieldTrivia.get(index), unit.origin(field))), pending);
			}
		}
		List<Element> declared = new ArrayList<>(ElementFilter.constructorsIn(owner.getEnclosedElements()));
		Set<Element> references = new HashSet<>();
		SourcePosition origin = unit.origin(type);
		if (constructor != null) {
			BlockTree body = constructor.getBody();
			Block lowered = lowering.statements(statements, prologue.isEmpty() ? unit.start(body) + 1 : prologueEnd,
					unit.end(body) - 1);
			add(entries, lowered.entries(), pending);
			pending.addAll(lowered.closingComments());
			references.addAll(unit.assembly().references(constructor));
			origin = unit.origin(constructor);
		}
		for (int index = 0; index < components.size(); index++) {
			add(entries, List.of(assignToSelf(componentFields.get(index), name(components.get(index)))), pending);
		}
		if (!(entries.getLast().statement() instanceof Statement.Return)) {
			add(entries, List.of(Block.Entry.of(new Statement.Return(List.of(name("self"))))), pending);
		}
		fields.forEach(field -> references.addAll(unit.assembly().references(field)));
		unit.overloading().addChain(chain, declared, references);
		Statement declaration = new Statement.FunctionDeclaration(List.of(table, "new"), null,
				new FunctionBody(parameters, varargs, new Block(entries, pending)));
		return new UnitLowering.Member(declared, true, null, List.of(new Block.Entry(declaration, trivia, origin)),
				List.of(), references, unit.ownTable(owner));
	}

	/**
	 * An instance initializer block's statements, with the blank line before the block on the first and the comment
	 * after its closing brace on the last.
	 */
	private static List<Block.Entry> initializerEntries(Block lowered, Trivia around) {
		List<Block.Entry> entries = new ArrayList<>(lowered.entries());
		if (entries.isEmpty()) {
			return entries;
		}
		Block.Entry first = entries.getFirst();
		Trivia above = new Trivia(around.blankLineBefore(), first.trivia().leading(), first.trivia().trailing());
		entries.set(0, new Block.Entry(first.statement(), above, first.origin()));
		Block.Entry last = entries.getLast();
		if (around.trailing() != null && last.trivia().trailing() == null) {
			Trivia after = new Trivia(last.trivia().blankLineBefore(), last.trivia().leading(), around.trailing());
			entries.set(entries.size() - 1, new Block.Entry(last.statement(), after, last.origin()));
		}
		return entries;
	}

	/** Adds the entries, with the pending comments above the first of them, which then stop pending. */
	private static void add(List<Block.Entry> entries, List<Block.Entry> added, List<Comment> pending) {
		if (added.isEmpty()) {
			return;
		}
		entries.addAll(MemberComments.withFirstComments(added, pending));
		pending.clear();
	}

	/** {@code self.member = value}. */
	private static Block.Entry assignToSelf(String member, Expression value) {
		return Block.Entry.of(Statement.assignment(field(name("self"), member), value));
	}

	/** A constructor's statements as written, with an explicit {@code super(...)} but without the one javac adds. */
	private List<StatementTree> ownStatements(BlockTree body) {
		return body.getStatements().stream()
				.filter(statement -> !unit.isSynthetic(statement))
				.map(StatementTree.class::cast)
				.toList();
	}

	/** A constructor's statements without the {@code super()} call javac adds or that only calls Object's. */
	public List<StatementTree> realStatements(BlockTree body) {
		return body.getStatements().stream()
				.filter(statement -> !(unit.isSynthetic(statement) || isSuperCall(statement)))
				.map(StatementTree.class::cast)
				.toList();
	}

	/** Whether a statement calls the superclass's constructor without arguments, as javac adds or Object's. */
	public static boolean isSuperCall(StatementTree statement) {
		MethodInvocationTree call = superCall(statement);
		return call != null && call.getArguments().isEmpty();
	}

	/** The {@code super(...)} call a statement makes, or null. */
	private static @Nullable MethodInvocationTree superCall(StatementTree statement) {
		return statement instanceof ExpressionStatementTree expression
				&& expression.getExpression() instanceof MethodInvocationTree call
				&& call.getMethodSelect() instanceof IdentifierTree name
				&& name.getName().contentEquals("super") ? call : null;
	}
}
