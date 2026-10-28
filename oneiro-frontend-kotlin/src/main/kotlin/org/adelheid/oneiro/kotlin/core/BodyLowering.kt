package org.adelheid.oneiro.kotlin.core

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.adelheid.oneiro.kotlin.language.Branches
import org.adelheid.oneiro.kotlin.language.Characters
import org.adelheid.oneiro.kotlin.language.Destructuring
import org.adelheid.oneiro.kotlin.language.Exceptions
import org.adelheid.oneiro.kotlin.language.Lambdas
import org.adelheid.oneiro.kotlin.language.Loops
import org.adelheid.oneiro.kotlin.language.ObjectExpressions
import org.adelheid.oneiro.kotlin.language.Operators
import org.adelheid.oneiro.kotlin.language.TypeTests
import org.adelheid.oneiro.kotlin.library.CollectionBuilders
import org.adelheid.oneiro.kotlin.library.CollectionLoops
import org.adelheid.oneiro.kotlin.library.Comparators
import org.adelheid.oneiro.kotlin.library.LibraryArguments
import org.adelheid.oneiro.kotlin.library.LibraryCalls
import org.adelheid.oneiro.kotlin.library.MapEntries
import org.adelheid.oneiro.kotlin.library.Preconditions
import org.adelheid.oneiro.kotlin.library.ScopeFunctions
import org.adelheid.oneiro.kotlin.library.StringBuilding
import org.adelheid.oneiro.kotlin.plugin.ApiCalls
import org.adelheid.oneiro.kotlin.plugin.PluginClass
import org.adelheid.oneiro.kotlin.plugin.ReturnValues
import org.adelheid.oneiro.lowering.TableFolding
import org.adelheid.oneiro.lowering.client.ClientLimits
import org.adelheid.oneiro.lowering.translation.IndexArithmetic
import org.adelheid.oneiro.lowering.translation.LuaPatterns
import org.adelheid.oneiro.lowering.translation.PlainValues
import org.adelheid.oneiro.lua.BinaryOperator
import org.adelheid.oneiro.lua.Block
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.Statement
import org.adelheid.oneiro.lua.Trivia
import org.adelheid.oneiro.lua.UnaryOperator
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.analysis.api.resolution.KaCallableMemberCall
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitInvokeCall
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.successfulCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaEnumEntrySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaJavaFieldSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaLocalVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaReceiverParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSamConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtAnnotatedExpression
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBreakExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtContinueExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtInstanceExpressionWithLabel
import org.jetbrains.kotlin.psi.KtIsExpression
import org.jetbrains.kotlin.psi.KtLabeledExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtObjectLiteralExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtSuperExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtTryExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.KtWhileExpression
import org.jetbrains.kotlin.psi.KtWhileExpressionBase
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

/**
 * Lowers the statements and expressions of one function, or one property's value, into the Lua tree.
 *
 * @param owner the class or object whose code this is, or null for code outside any class
 * @param instance whether the code runs on an object, which the Lua calls `self`
 */
internal class BodyLowering(
	val unit: FileLowering,
	private val owner: KtClassOrObject?,
	private val instance: Boolean,
) {

	private val source = unit.source
	val types = unit.types
	val api = ApiCalls(unit)
	val library = LibraryCalls(this)
	val loops = Loops(this)
	private val branches = Branches(this)
	val operators = Operators(this)
	private val exceptions = Exceptions(this)
	val lambdas = Lambdas(this)
	val strings = StringBuilding(this)
	val chars = Characters(this)

	/** Whether a type is an exception's, which in Lua is only its message. */
	fun isThrowable(type: KaClassSymbol): Boolean = exceptions.isThrowable(type)
	val typeTests = TypeTests(this)
	val objects = ObjectExpressions(this)
	val returnValues = ReturnValues(this)
	val comparators = Comparators(this)
	private val chains = CollectionLoops(this)
	private val preconditions = Preconditions(this)
	private val scopes = ScopeFunctions(this)
	private val builders = CollectionBuilders(this)
	val destructuring = Destructuring(this)

	/**
	 * The statement being lowered, the code that values inside it need run before it, such as an if's or a chain's,
	 * and those values; see [hoisted].
	 */
	private class Hoisting(val root: KtExpression) {
		val entries = mutableListOf<Block.Entry>()
		val values = mutableListOf<KtExpression>()
	}

	private var hoisting: Hoisting? = null
	// The names of the locals made up in this function that last to the end of their block; see [invent]
	private val inventedHere = mutableSetOf<String>()
	// What expressions read more than once lower to, once lowered; see [reused]
	private val reusedValues = mutableMapOf<KtExpression, Expression>()

	/** A lowering for code nested in this one's, such as a lambda's, which runs on the same object. */
	fun nested(): BodyLowering = BodyLowering(unit, owner, instance)

	/**
	 * A block's statements, each with the comments above it and after it, and the comments that close the block. With
	 * [last], the final statement is lowered by it instead, as a branch that gives a value hands its last expression
	 * on. A statement Oneiro rejects is reported, and the statements after it are still checked.
	 *
	 * @param endsFunction whether the block is a function's body, after whose last statement nothing runs
	 */
	fun block(block: KtBlockExpression, last: ((KtExpression) -> List<Statement>)? = null,
		endsFunction: Boolean = false): Block {
		val entries = mutableListOf<Block.Entry>()
		val statements = block.statements
		for ((index, statement) in statements.withIndex()) {
			val lower = if (index == statements.lastIndex && last != null) lowerBy(last) else null
			try {
				entries += entries(statement, lower, endsFunction && index == statements.lastIndex)
			} catch (rejection: Rejection) {
				unit.reject(rejection)
			}
		}
		// Comments after the last statement, before the closing brace, close the block
		val closing = generateSequence(block.rBrace?.prevSibling) { it.prevSibling }
			.takeWhile { it !is KtExpression }
			.filterIsInstance<PsiComment>()
			.toList()
			.reversed()
			.flatMap(source::closing)
		return Block(TableFolding.fold(entries), closing)
	}

	/**
	 * A statement as block entries: the first carries the comments above it, the last the one after it. Entries that
	 * bring comments of their own, as a finally block's statements do, keep them.
	 *
	 * @param lower how to lower the statement, when not as a statement of its own
	 * @param endsFunction whether the statement is the last of a function's body
	 */
	private fun entries(statement: KtExpression, lower: ((KtExpression) -> List<Block.Entry>)?,
		endsFunction: Boolean = false): List<Block.Entry> {
		val blankLine = source.blankLineBefore(statement)
		val leading = source.leading(statement)
		val lowered = hoistingInto(statement) { lower?.invoke(statement) ?: statementEntries(statement, endsFunction) }
		val trailing = source.trailing(statement)
		val origin = source.position(unit.codeStart(statement))
		return lowered.mapIndexed { index, entry ->
			val own = entry.trivia()
			val trivia = Trivia(index == 0 && blankLine || own.blankLineBefore(),
				if (index == 0) leading + own.leading() else own.leading(),
				if (index == lowered.lastIndex && trailing != null) trailing else own.trailing())
			Block.Entry(entry.statement(), trivia, entry.origin() ?: origin)
		}
	}

	/** Lowers a statement, after the code that values inside it need run before it; see [hoisted]. */
	private fun hoistingInto(statement: KtExpression, lower: () -> List<Block.Entry>): List<Block.Entry> {
		val inner = Hoisting(statement)
		val lowered = within(inner, lower)
		return inner.entries + lowered
	}

	/** Lowers code whose hoisted values go before the statement [inner] stands for. */
	private fun <T> within(inner: Hoisting, lower: () -> T): T {
		val outer = hoisting
		hoisting = inner
		try {
			return lower()
		} finally {
			hoisting = outer
		}
	}

	/**
	 * A function's expression body as its statements: its return, or the statement it is for a function that returns
	 * nothing, after what the ifs, whens and chains inside it run before it; see [hoisted].
	 */
	fun expressionBody(expression: KtExpression, returnsNothing: Boolean): List<Block.Entry> =
		hoistingInto(expression) {
			(if (returnsNothing) expressionStatement(expression) else returned(expression)).map(Block.Entry::of)
		}

	private fun lowerBy(last: (KtExpression) -> List<Statement>): (KtExpression) -> List<Block.Entry> =
		{ statement -> last(statement).map(Block.Entry::of) }

	/** A branch's or a loop's body as a block: a block's statements, or a single statement. */
	fun block(expression: KtExpression, last: ((KtExpression) -> List<Statement>)? = null): Block =
		if (expression is KtBlockExpression) {
			block(expression, last)
		} else {
			Block(entries(expression, last?.let(::lowerBy)), listOf())
		}

	/**
	 * A statement as block entries, which a `try` with a finally block and a scope function's lambda make with comments
	 * of their own.
	 */
	private fun statementEntries(statement: KtExpression, endsFunction: Boolean): List<Block.Entry> {
		if (statement is KtTryExpression) {
			return exceptions.tryStatement(statement)
		}
		// target = receiver.apply { ... } on a target that reading again gives the same, a name or a property
		val target = (statement as? KtBinaryExpression)?.takeIf { it.operationToken == KtTokens.EQ }?.left
		val scope = (statement as? KtBinaryExpression)?.right?.let(scopes::scope)
		if (target != null && scope != null) {
			val assigned = assignmentTarget(target)
			if (PlainValues.isPlain(assigned)) {
				scopes.assigned(scope, assigned)?.let { return it }
			}
		}
		return scopes.entries(statement, endsFunction) ?: statement(statement).map(Block.Entry::of)
	}

	/**
	 * A value assigned to a target, such as a class's property, `self.box = ...`: a scope function's code works on the
	 * target, and an if's, a when's or a chain's value is assigned in each branch.
	 */
	fun assigned(value: KtExpression, target: Expression): List<Block.Entry> {
		scopes.scope(value)?.let { scope -> scopes.assigned(scope, target)?.let { return it } }
		return handedOn(value) { Statement.assignment(target, it) }.map(Block.Entry::of)
	}

	fun statement(statement: KtExpression): List<Statement> = when (statement) {
		is KtProperty -> local(statement)
		is KtDestructuringDeclaration -> destructured(statement)
		is KtClass -> objects.localClass(statement)
		is KtLabeledExpression -> statement(statement.baseExpression ?: throw Rejection(statement, EMPTY))
		// An annotation, such as @Suppress, changes nothing in the Lua
		is KtAnnotatedExpression -> statement(statement.baseExpression ?: throw Rejection(statement, EMPTY))
		is KtIfExpression -> listOf(branches.ifStatement(statement))
		is KtWhenExpression -> branches.whenStatement(statement)
		is KtReturnExpression -> returnStatement(statement)
		is KtForExpression -> listOf(loops.forLoop(statement))
		is KtThrowExpression -> listOf(exceptions.throwStatement(statement))
		is KtTryExpression -> exceptions.tryStatement(statement).map { it.statement() }
		is KtWhileExpression -> listOf(loops.whileLoop(statement))
		is KtDoWhileExpression -> listOf(loops.doWhileLoop(statement))
		is KtBreakExpression -> listOf(loops.breakStatement(statement))
		is KtContinueExpression -> listOf(loops.continueStatement(statement))
		else -> expressionStatement(statement)
	}

	/**
	 * `val (a, b) = value`: a Lua function's several values, the two lists of `partition`, or the parts of a pair, a
	 * data class's value or a list.
	 */
	private fun destructured(declaration: KtDestructuringDeclaration): List<Statement> {
		val initializer = declaration.initializer ?: throw Rejection(declaration, EMPTY)
		if (returnValues.components(with(unit.session) { initializer.expressionType }) != null) {
			return listOf(returnValues.destructured(declaration))
		}
		val chain = chains.chain(initializer)
		if (chain != null && chain.terminal.name == "partition") {
			// The lists the declaration names, or names of their own for those it leaves out
			val names = listOf("passed", "failed").mapIndexed { index, preferred ->
				val entry = declaration.entries.getOrNull(index)?.takeIf { it.name != "_" }
				if (entry != null) unit.localName(entry, entry.name!!) else invent(preferred, declaration)
			}
			return chains.lower(chain, CollectionLoops.Destination.Parts(names))
		}
		return destructuring.local(declaration)
	}

	/**
	 * `return value`; a value from `if` or `when` is returned in each branch. Inside a lambda only a `return` that
	 * names the lambda, `return@forEach`, leaves it, as Lua's leaves the function it is in.
	 */
	private fun returnStatement(statement: KtReturnExpression): List<Statement> {
		val enclosing = PsiTreeUtil.getParentOfType(statement, KtFunctionLiteral::class.java,
			KtNamedFunction::class.java)
		if (enclosing is KtFunctionLiteral && enclosing !in unit.inlinedLambdas && statement.getTargetLabel() == null) {
			throw Rejection(statement, "In Lua this return would leave only the lambda it is in, not the function"
				+ " around it; return from the lambda with return@label, or loop with for instead.")
		}
		val value = statement.returnedExpression ?: return listOf(Statement.Return(listOf()))
		return returned(value)
	}

	/** The statements that return a value: one from `if` or `when` in each branch, and one that jumps as it is. */
	fun returned(value: KtExpression): List<Statement> {
		if (value is KtAnnotatedExpression) {
			return returned(value.baseExpression ?: throw Rejection(value, EMPTY))
		}
		if (Branches.jumps(value)) {
			return statement(value)
		}
		if (value is KtTryExpression) {
			return exceptions.tryStatement(value, Exceptions.TryValue(::returned, true)).map { it.statement() }
		}
		if (returnValues.components(with(unit.session) { value.expressionType }) != null) {
			return listOf(Statement.Return(returnValues.returned(value)))
		}
		chains.chain(value)?.let { return chains.lower(it, CollectionLoops.Destination.Returned) }
		scopes.scope(value)?.let { scope ->
			return scopes.statements(scope, { lowered -> Statement.Return(listOf(lowered)) }, true)
		}
		return delivered(value) { Statement.Return(listOf(it)) } ?: listOf(Statement.Return(listOf(expression(value))))
	}

	/**
	 * A value from `if` or `when` as statements that hand each branch's value to the sink; null for any other value,
	 * and for an `if` that Lua can say as `c and a or b`.
	 */
	private fun delivered(value: KtExpression, sink: (Expression) -> Statement): List<Statement>? {
		if (value is KtIfExpression && isSafeConditional(value)) {
			return null
		}
		chains.chain(value)?.let { return chains.lower(it, CollectionLoops.Destination.Sink(sink)) }
		if (isUse(value)) {
			return exceptions.use(value as KtQualifiedExpression, sink)
		}
		if (value is KtTryExpression) {
			val last = { last: KtExpression -> handedOn(last, sink) }
			return exceptions.tryStatement(value, Exceptions.TryValue(last, false)).map { it.statement() }
		}
		if (strings.isBuildString(value)) {
			return strings.buildString(value as KtCallExpression, sink)
		}
		builders.builder(value)?.let { builder ->
			val name = collectionName(builders.holderName(builder), destinationName(sink), value)
			return builders.built(value, name) + sink(Expression.name(name))
		}
		if (isRun(value)) {
			return runValue(value as KtCallExpression, sink)
		}
		scopes.scope(value)?.let { return scopes.statements(it, sink) }
		return branches.deliver(value, sink)
	}

	/**
	 * The statements that hand a value to the sink: an `if` or `when` hands each branch's on, and code that jumps runs
	 * as it is.
	 */
	fun handedOn(value: KtExpression, sink: (Expression) -> Statement): List<Statement> = when {
		value is KtAnnotatedExpression -> handedOn(value.baseExpression ?: throw Rejection(value, EMPTY), sink)
		Branches.jumps(value) -> statement(value)
		else -> delivered(value, sink) ?: listOf(sink(expression(value)))
	}

	/** Whether an expression is `run { ... }` with no receiver, a block of code with locals of its own. */
	private fun isRun(expression: KtExpression): Boolean = with(unit.session) {
		val call = expression as? KtCallExpression ?: return false
		val function = call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol ?: return false
		function.callableId?.callableName?.asString() == "run" && isLibrary(function) &&
			call.lambdaArguments.size == 1 && call.valueArguments.size == 1
	}

	/** `run { ... }` as a statement: Lua's `do ... end`, whose locals end with it. */
	private fun scoped(call: KtCallExpression): Statement = Statement.Do(runBlock(call, null))

	/**
	 * `run { ... }` whose value goes to the sink: its code, whose last expression is handed to the sink, in a `do`
	 * block when it declares locals of its own, which end with it.
	 */
	private fun runValue(call: KtCallExpression, sink: (Expression) -> Statement): List<Statement> {
		val code = runBlock(call) { last -> handedOn(last, sink) }
		val declares = code.entries().any {
			it.statement() is Statement.Local || it.statement() is Statement.LocalFunction
		}
		return if (declares || code.closingComments().isNotEmpty()) listOf(Statement.Do(code))
		else code.entries().map { it.statement() }
	}

	/** The code of `run { ... }`, its last expression lowered by [last] when it gives one. */
	private fun runBlock(call: KtCallExpression, last: ((KtExpression) -> List<Statement>)?): Block {
		val lambda = call.lambdaArguments.single().getLambdaExpression() ?: throw Rejection(call, EMPTY)
		val literal = lambda.functionLiteral
		unit.inlinedLambdas += literal
		return block(literal.bodyBlockExpression ?: throw Rejection(lambda, EMPTY), last)
	}

	/** Whether an expression is `repeat(n) { ... }`. */
	private fun isRepeat(expression: KtExpression): Boolean = with(unit.session) {
		val call = expression as? KtCallExpression ?: return false
		val id = call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol?.callableId ?: return false
		id.callableName.asString() == "repeat" && id.packageName.asString() == "kotlin" && id.classId == null &&
			call.valueArguments.size == 2
	}

	/** Whether an expression is `resource.use { ... }`, which closes the resource after the lambda's code. */
	private fun isUse(expression: KtExpression): Boolean = with(unit.session) {
		val call = (expression as? KtQualifiedExpression)?.selectorExpression as? KtCallExpression ?: return false
		val function = call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol ?: return false
		function.callableId?.callableName?.asString() == "use" && isLibrary(function)
	}

	/**
	 * A name for a local the Lua needs that the source does not have, declared among the statements of a block, such
	 * as `pcall`'s `ok`: the preferred one, unless it would hide a name the code around reads or another local made up
	 * in this function, then the preferred one with a number, from 2; see [FileLowering.namesAround]. No local made up
	 * later in this function takes it, so none hides it, in the block or inside it.
	 *
	 * @param others names it must not take besides, such as those of the loop it goes before
	 */
	fun invent(preferred: String, where: PsiElement, others: Set<String> = setOf()): String {
		val name = numbered(preferred, where, others)
		inventedHere += name
		return name
	}

	/**
	 * A name for a variable of a loop or a parameter of a function the Lua makes, which lives only inside it: made as
	 * [invent] makes one, but free again after it, as Lua's loops one after another each name their key `key`.
	 */
	fun inventInner(preferred: String, where: PsiElement, others: Set<String> = setOf()): String =
		numbered(preferred, where, others)

	/** Whether a made-up local could take a name as it is, without a number; see [invent]. */
	fun isFree(name: String, where: PsiElement, others: Set<String> = setOf()): Boolean =
		unit.names.luaName(name) !in taken(where, others)

	private fun taken(where: PsiElement, others: Set<String>): Set<String> =
		unit.namesAround(where) + inventedHere + others

	private fun numbered(preferred: String, where: PsiElement, others: Set<String>): String {
		val taken = taken(where, others)
		val base = unit.names.luaName(preferred)
		var name = base
		var number = 2
		while (name in taken) {
			name = base + number++
		}
		unit.inventedNames += name
		return name
	}

	/**
	 * The name of a list, a set or a map that a made-up local holds: the name of where it goes where that is free, the
	 * parameter it is handed to or the property it becomes, `texts` or `lines`; otherwise the kind of value it is,
	 * `list`, with a number where that is taken.
	 *
	 * @param destination the name of where the value goes, or null where it goes to no name, as a return
	 */
	fun collectionName(kind: String, destination: String?, where: PsiElement, others: Set<String> = setOf()): String =
		invent(destination?.takeIf { isFree(it, where, others) } ?: kind, where, others)

	/**
	 * The name of the property a sink assigns its value to, `lines` for `self.lines = value` and for
	 * `self.lines[section] = value`; null for a sink that returns the value or assigns it to a local.
	 */
	fun destinationName(sink: (Expression) -> Statement): String? {
		// Sinks only make the statement, so making one to look at changes nothing
		val target = (sink(Expression.Nil()) as? Statement.Assignment)?.targets()?.singleOrNull()
		return when (target) {
			is Expression.Field -> target.name()
			is Expression.Index -> (target.target() as? Expression.Field)?.name()
			else -> null
		}
	}

	/**
	 * The declaration a local's or a parameter's name belongs to: its own, or for a lambda's implicit `it`, the
	 * lambda's, the innermost around the name that takes it.
	 */
	fun declarationOf(symbol: KaSymbol, reference: KtElement): PsiElement? = with(unit.session) {
		symbol.psi ?: generateSequence(PsiTreeUtil.getParentOfType(reference, KtFunctionLiteral::class.java)) {
			PsiTreeUtil.getParentOfType(it, KtFunctionLiteral::class.java)
		}.firstOrNull { !it.hasParameterSpecification() && it.symbol.valueParameters.isNotEmpty() }
	}

	/** Whether code names a declaration, such as a catch's parameter. */
	fun uses(code: PsiElement?, declaration: PsiElement): Boolean = code != null &&
		PsiTreeUtil.collectElementsOfType(code, KtNameReferenceExpression::class.java).any {
			with(unit.session) { it.resolveSymbol()?.psi } == declaration
		}

	/**
	 * An expression used as a statement: a call, an assignment, `++` and `--`, or a call that changes a collection,
	 * such as `list.add(x)`.
	 */
	fun expressionStatement(expression: KtExpression): List<Statement> = when {
		expression is KtBinaryExpression && expression.operationToken in ASSIGNMENTS -> assignment(expression)
		expression is KtPostfixExpression && expression.operationToken in STEPS ->
			listOf(step(expression.baseExpression, expression.operationToken == KtTokens.PLUSPLUS, expression))
		expression is KtPrefixExpression && expression.operationToken in STEPS ->
			listOf(step(expression.baseExpression, expression.operationToken == KtTokens.PLUSPLUS, expression))
		expression is KtSafeQualifiedExpression -> safeStatement(expression)
		isUse(expression) -> exceptions.use(expression as KtQualifiedExpression, null)
		isRun(expression) -> listOf(scoped(expression as KtCallExpression))
		isRepeat(expression) -> listOf(loops.repeat(expression as KtCallExpression))
		preconditions.isCondition(expression) -> listOf(preconditions.condition(expression as KtCallExpression))
		preconditions.isNotNull(expression) -> listOf(preconditions.notNullStatement(expression as KtCallExpression))
		scopes.scope(expression) != null -> scopes.statements(scopes.scope(expression)!!, null)
		else -> strings.appends(expression)
			?: chains.chain(expression)?.let { chains.lower(it, CollectionLoops.Destination.Effect) }
			?: collectionStatement(expression) ?: listOf(callStatement(expression))
	}

	/** An expression whose value goes nowhere, which Lua takes as a statement only when it is a call. */
	private fun callStatement(expression: KtExpression): Statement = when (val lowered = expression(expression)) {
		is Expression.Call, is Expression.MethodCall -> Statement.CallStatement(lowered)
		else -> throw Rejection(expression, "In Lua a statement is a call or an assignment; this expression's value"
			+ " would go nowhere, so remove it or use it.")
	}

	/**
	 * `receiver.member(...)` as a statement, or `member(...)` on a receiver left unnamed, when the member is one of the
	 * library's that changes a collection.
	 */
	private fun collectionStatement(expression: KtExpression): List<Statement>? = with(unit.session) {
		val qualified = expression as? KtDotQualifiedExpression
		val call = (qualified?.selectorExpression ?: expression) as? KtCallExpression ?: return null
		val resolved = call.resolveToCall()?.successfulFunctionCallOrNull() ?: return null
		if (!isLibrary(resolved.symbol)) {
			return null
		}
		val receiver = libraryReceiver(qualified?.receiverExpression, call) ?: return null
		library.statement(expression, resolved.symbol, receiver, libraryArguments(resolved, call))
	}

	/**
	 * `val x = value` as `local x = value`. A value from `if` or `when` is assigned in each branch, and
	 * `val x = value ?: return` leaves when the value is null.
	 */
	private fun local(property: KtProperty): List<Statement> {
		val name = unit.localName(property, property.name!!)
		val initializer = property.initializer ?: return listOf(Statement.Local(listOf(name), listOf()))
		returnValues.components(with(unit.session) { initializer.expressionType })?.let { components ->
			return returnValues.local(property, initializer, components)
		}
		return localValue(name, initializer)
	}

	/**
	 * The statements that declare a local of this name with a value, which a chain, an `if` or `when`, a scope
	 * function or a builder gives with statements of its own.
	 */
	fun localValue(name: String, initializer: KtExpression): List<Statement> {
		if (initializer is KtAnnotatedExpression) {
			return localValue(name, initializer.baseExpression ?: throw Rejection(initializer, EMPTY))
		}
		val local = Expression.name(name)
		chains.chain(initializer)?.let { return chains.lower(it, CollectionLoops.Destination.Local(name)) }
		if (preconditions.isNotNull(initializer)) {
			return preconditions.notNullLocal(name, initializer as KtCallExpression)
		}
		scopes.test(initializer)?.let { return scopes.testLocal(name, it) }
		if (builders.builder(initializer) != null) {
			return builders.built(initializer, name)
		}
		delivered(initializer) { Statement.assignment(local, it) }?.let { statements ->
			// A value handed on in the last statement, as run { value } and buildString give, is declared there
			val last = statements.lastOrNull() as? Statement.Assignment
			if (last != null && last.targets() == listOf(local)) {
				return statements.dropLast(1) + Statement.Local(listOf(name), last.values())
			}
			return listOf(Statement.Local(listOf(name), listOf())) + statements
		}
		val elvis = initializer as? KtBinaryExpression
		val jump = elvis?.right
		// value ?: return, ?: throw ... and ?: error(...) leave when the value is nil, and only then
		val leaves = jump != null && (Branches.jumps(jump) ||
			with(unit.session) { jump.expressionType }?.let(types::isNothing) == true)
		if (elvis?.operationToken == KtTokens.ELVIS && leaves) {
			val value = expression(elvis.left ?: throw Rejection(elvis, EMPTY))
			val absent = Expression.binary(local, BinaryOperator.EQUAL, Expression.Nil())
			return listOf(Statement.Local(listOf(name), listOf(value)),
				Statement.ifThen(absent, Block(statement(jump).map(Block.Entry::of), listOf())))
		}
		return listOf(Statement.Local(listOf(name), listOf(expression(initializer))))
	}

	/**
	 * `target = value`, and `target += value` and its kin as `target = target + value`. A value from `if` or `when` is
	 * assigned in each branch.
	 */
	private fun assignment(assignment: KtBinaryExpression): List<Statement> {
		val targetTree = assignment.left ?: throw Rejection(assignment, EMPTY)
		val valueTree = assignment.right ?: throw Rejection(assignment, EMPTY)
		if (assignment.operationToken == KtTokens.EQ) {
			val target = assignmentTarget(targetTree)
			return delivered(valueTree) { Statement.assignment(target, it) }
				?: listOf(Statement.assignment(target, expression(valueTree)))
		}
		val target = readTarget(targetTree)
		val targetType = with(unit.session) { targetTree.expressionType }
		if (targetType != null && types.isString(targetType) && assignment.operationToken == KtTokens.PLUSEQ) {
			// text += value appends the value as text
			return listOf(Statement.assignment(target, Expression.binary(target, BinaryOperator.CONCAT,
				strings.concatOperand(valueTree))))
		}
		// x /= y and x %= y round as Kotlin's / and % do
		when (assignment.operationToken) {
			KtTokens.DIVEQ ->
				return listOf(Statement.assignment(target, operators.division(targetTree, valueTree, target)))
			KtTokens.PERCEQ ->
				return listOf(Statement.assignment(target, operators.remainder(targetTree, valueTree, target)))
		}
		val value = expression(valueTree)
		val operator = when (assignment.operationToken) {
			KtTokens.PLUSEQ -> BinaryOperator.ADD
			KtTokens.MINUSEQ -> BinaryOperator.SUBTRACT
			KtTokens.MULTEQ -> BinaryOperator.MULTIPLY
			else -> throw Rejection.notYet(assignment, "The operator ${assignment.operationReference.text}")
		}
		return listOf(Statement.assignment(target, Expression.binary(target, operator, value)))
	}

	/**
	 * The target of an assignment that reads it as well, `target = target + value`, with the parts Lua would work out
	 * twice, such as a call's value or a position, read once, into locals before the statement; see [reused].
	 */
	private fun readTarget(targetTree: KtExpression): Expression {
		val target = assignmentTarget(targetTree)
		val written = KtPsiUtil.safeDeparenthesize(targetTree)
		return when {
			target is Expression.Index && written is KtArrayAccessExpression -> {
				val holder = written.arrayExpression ?: throw Rejection(written, EMPTY)
				val key = written.indexExpressions.single()
				// A position of a list or an array is one higher in Lua, so its local is the position Lua reads
				val position = with(unit.session) { holder.expressionType }?.let(types::isMap) != true
				Expression.Index(reused(target.target(), holder, READ_TARGET, valueName(holder)),
					reused(target.key(), key, READ_TARGET, if (position) "index" else valueName(key)))
			}
			target is Expression.Field && written is KtDotQualifiedExpression -> {
				val holder = written.receiverExpression
				Expression.Field(reused(target.target(), holder, READ_TARGET, valueName(holder)), target.name())
			}
			else -> target
		}
	}

	/** What an assignment assigns to, which must be no property of settings, and no field of a copy. */
	private fun assignmentTarget(targetTree: KtExpression): Expression {
		val assigned = with(unit.session) {
			((targetTree as? KtDotQualifiedExpression)?.selectorExpression ?: targetTree)
				.let { it as? KtNameReferenceExpression }?.resolveSymbol()?.psi
		}
		if (assigned is KtProperty && unit.isSettingsProperty(assigned)) {
			throw Rejection(targetTree, PluginClass.SETTINGS_VALUE + "assignment.")
		}
		checkAssigned(targetTree)
		return expression(targetTree)
	}

	/**
	 * Assigning a field of the RS3 bindings that moderation reviews, such as a menu entry's `overrideText`, or a field
	 * of a value the client hands out as a copy.
	 */
	private fun checkAssigned(target: KtExpression): Unit = with(unit.session) {
		val name = (if (target is KtDotQualifiedExpression) target.selectorExpression else target)
			as? KtNameReferenceExpression ?: return
		val holder = (target as? KtDotQualifiedExpression)?.receiverExpression?.let(KtPsiUtil::safeDeparenthesize)
		val holderName = (holder as? KtDotQualifiedExpression)?.selectorExpression ?: holder
		val copied = (holderName as? KtNameReferenceExpression)?.resolveSymbol() as? KaJavaFieldSymbol
		if (holder != null && copied != null) {
			api.checkNotIntoCopy(holder, copied, name.getReferencedName(), target)
		}
		val field = name.resolveSymbol() as? KaJavaFieldSymbol ?: return
		val owner = api.owner(field) ?: return
		if (api.isApi(owner)) {
			api.assigned(field, owner, target)
		}
	}

	/**
	 * `x!!`: the value itself, which Lua uses as it is; Lua raises an error where a nil is used as a value, as Kotlin
	 * raises one at the `!!`.
	 */
	private fun postfix(postfix: KtPostfixExpression): Expression {
		if (postfix.operationToken != KtTokens.EXCLEXCL) {
			throw Rejection(postfix, "Lua has no increment or decrement expressions; put it on a line of its own.")
		}
		return expression(postfix.baseExpression ?: throw Rejection(postfix, EMPTY))
	}

	/** `x++` and `x--` as statements: `x = x + 1`, and for a char the next or the previous one. */
	private fun step(base: KtExpression?, up: Boolean, where: KtElement): Statement {
		val variable = base ?: throw Rejection(where, EMPTY)
		val target = readTarget(variable)
		if (chars.isChar(variable)) {
			return Statement.assignment(target, chars.stepped(target, up))
		}
		val operator = if (up) BinaryOperator.ADD else BinaryOperator.SUBTRACT
		return Statement.assignment(target, Expression.binary(target, operator, Expression.integer(1)))
	}

	// ---- Expressions

	/** A library member's receiver: its expression, or the Lua name of one left unnamed. */
	fun expression(receiver: Receiver): Expression = when (receiver) {
		is Receiver.Written -> expression(receiver.expression)
		is Receiver.Unnamed -> receiver.lua
	}

	/** A library member's receiver read more than once; see [reused]. One left unnamed is a name, read as it is. */
	fun reused(receiver: Receiver, what: String): Expression = when (receiver) {
		is Receiver.Written -> reused(receiver.expression, what)
		is Receiver.Unnamed -> receiver.lua
	}

	fun expression(expression: KtExpression): Expression = reusedValues[expression] ?: when (expression) {
		is KtConstantExpression -> constant(expression)
		is KtStringTemplateExpression -> string(expression)
		is KtNameReferenceExpression -> reference(expression, null)
		is KtDotQualifiedExpression -> qualified(expression)
		is KtCallExpression -> call(expression, null)
		is KtThisExpression -> self(expression)
		is KtSuperExpression -> self(expression)
		is KtParenthesizedExpression -> expression(expression.expression ?: throw Rejection(expression, EMPTY))
		is KtAnnotatedExpression -> expression(expression.baseExpression ?: throw Rejection(expression, EMPTY))
		is KtPrefixExpression -> operators.prefix(expression)
		is KtBinaryExpression -> operators.binary(expression)
		is KtArrayAccessExpression -> index(expression)
		is KtIfExpression -> conditional(expression)
		is KtIsExpression -> typeTests.isExpression(expression)
		is KtBinaryExpressionWithTypeRHS -> typeTests.cast(expression)
		is KtPostfixExpression -> postfix(expression)
		is KtLambdaExpression -> lambdas.lambda(expression)
		is KtObjectLiteralExpression -> objects.literal(expression)
		is KtCallableReferenceExpression -> lambdas.reference(expression)
		is KtClassLiteralExpression -> throw Rejection(expression, NO_CLASSES)
		is KtSafeQualifiedExpression -> safeCall(expression)
		is KtThrowExpression -> exceptions.errorCall(expression.thrownExpression ?: throw Rejection(expression, EMPTY))
		is KtWhenExpression -> hoisted(expression)
		else -> throw Rejection.notYet(expression, "This kind of expression")
	}

	/**
	 * An `if` or `when` inside an expression, whose branches Lua cannot say as one: a local before the statement takes
	 * its value, which each branch assigns, `local value` and `if ... then value = ... end`; see [hoisted].
	 */
	private fun hoisted(value: KtExpression): Expression {
		val kind = if (value is KtWhenExpression) "when" else "if"
		return hoistedInto(value, hoistedName(value), "In Lua this $kind is a statement",
			"give its value to a local of its own first, val x = $kind ...") { name ->
			val branched = branches.deliver(value) { Statement.assignment(Expression.name(name), it) }
				?: throw IllegalStateException("an if or when that gives no value: ${value.text}")
			(listOf(Statement.Local(listOf(name), listOf())) + branched).map(Block.Entry::of)
		}
	}

	/**
	 * A chain of collection functions inside an expression, `if (npcs.any { ... })` or `count { ... } + 1`: as for an
	 * `if` or `when` there, a local before the statement takes its value, named after the call that gives it; see
	 * [hoisted]. Null for a value that is no chain.
	 */
	fun hoistedChain(value: KtExpression): Expression? {
		val chain = chains.chain(value) ?: return null
		val kind = chains.resultName(chain)
		val destination = if (kind in COLLECTIONS) parameterName(value, ownOnly = true) else null
		val preferred = destination?.takeIf { isFree(it, value) } ?: kind
		return hoistedInto(value, preferred, LibraryCalls.CHAIN_LOOP) { name ->
			chains.lower(chain, CollectionLoops.Destination.Local(name)).map(Block.Entry::of)
		}
	}

	/**
	 * A value inside an expression that Lua makes with statements: the statements [lower] makes, which give the value
	 * to a local of the name it is passed, run before the statement, and the expression reads the local; see
	 * [hoisted].
	 *
	 * @param what what Lua makes of the value, for the message where it cannot run before the statement
	 * @param instead what to write instead, for that message
	 */
	fun hoistedInto(value: KtExpression, preferred: String, what: String, instead: String = LOCAL_FIRST,
		lower: (String) -> List<Block.Entry>): Expression.Name {
		lateinit var name: String
		hoisted(value, what, instead) {
			name = invent(preferred, value)
			lower(name)
		}
		return Expression.name(name)
	}

	/**
	 * Runs the code [lower] makes for a value inside an expression before the statement the value is in, where
	 * nothing the statement does would see the difference; otherwise rejects the value, saying why not.
	 *
	 * @param what what Lua makes of the value, for the message where it cannot run before the statement
	 * @param instead what to write instead, for that message
	 * @param at where that message points
	 */
	fun hoisted(value: KtExpression, what: String, instead: String = LOCAL_FIRST, at: PsiElement = value,
		lower: () -> List<Block.Entry>) {
		val around = hoisting
		whyNotHoisted(value, around)?.let { why ->
			throw Rejection(at, "$what, which can run before the code around it only where nothing would see the"
				+ " difference, but $why; $instead")
		}
		around!!.entries += lower()
		around.values += value
	}

	/**
	 * A value the Lua reads more than once, lowered once: as it is when reading it again gives the same (see
	 * [PlainValues]), and otherwise in a local before the statement, which each read names, after the value (see
	 * [valueName]). Lowering the expression again gives the same.
	 *
	 * @param what why the Lua reads the value more than once, for the message where it cannot go in a local first
	 */
	fun reused(value: KtExpression, what: String): Expression {
		reusedValues[value]?.let { return it }
		val read = reused(expression(value), value, what, valueName(value))
		reusedValues[value] = read
		return read
	}

	/** A value the Lua reads more than once, already lowered from [where]: as [reused] reads one. */
	fun reused(lowered: Expression, where: KtExpression, what: String, preferred: String): Expression {
		if (PlainValues.isPlain(lowered)) {
			return lowered
		}
		lateinit var name: String
		hoisted(where, "$what, so it goes into a local first", "store it in a local first.") {
			name = invent(preferred, where)
			listOf(Block.Entry.of(Statement.Local(listOf(name), listOf(lowered))))
		}
		return Expression.name(name)
	}

	/**
	 * `a && b` and `a || b`. When Lua can say the right side only with statements before it, such as a chain's loop,
	 * the whole test goes into a local before the statement, `local both = a`, and the right side's statements run
	 * inside `if both then`, which ends `both = b`, so that they run only when Kotlin's right side would; `either` and
	 * `if not either then` for `||`. That local is a hoisted value's; see [hoisted].
	 */
	fun shortCircuit(binary: KtBinaryExpression, left: KtExpression, right: KtExpression): Expression {
		val and = binary.operationToken == KtTokens.ANDAND
		val operator = if (and) BinaryOperator.AND else BinaryOperator.OR
		val leftValue = expression(left)
		val inner = Hoisting(right)
		val rightValue = within(inner) { expression(right) }
		if (inner.entries.isEmpty()) {
			return Expression.Binary(operator, leftValue, rightValue)
		}
		lateinit var local: Expression
		hoisted(binary, "In Lua the right side of this ${binary.operationReference.text} runs as statements",
			"give the test to a local of its own first, val x = ...", right) {
			val name = invent(if (and) "both" else "either", binary)
			local = Expression.name(name)
			val decided = Block(inner.entries + Block.Entry.of(Statement.assignment(local, rightValue)), listOf())
			val undecided = if (and) local else Expression.Unary(UnaryOperator.NOT, local)
			listOf(Block.Entry.of(Statement.Local(listOf(name), listOf(leftValue))),
				Block.Entry.of(Statement.ifThen(undecided, decided)))
		}
		return local
	}

	/**
	 * Why the code a value inside a statement needs cannot run before the statement, or null when it can: the
	 * statement must run the value each time it runs, not only when an `&&`, an `||`, a `?:`, a `?.` or a branch gets
	 * to it, nor once each turn of a loop or each time a lambda runs; and nothing the statement evaluates before the
	 * value may call a function, whose effects would then come after the value's code. The values already hoisted run
	 * before the statement too, and their calls with them.
	 */
	private fun whyNotHoisted(value: KtExpression, around: Hoisting?): String? {
		val outside = "here there is no statement for it to go before"
		if (around == null) {
			return outside
		}
		var node: PsiElement = value
		while (node != around.root) {
			val parent = node.parent ?: return outside
			val inside = { part: PsiElement? -> part != null && PsiTreeUtil.isAncestor(part, value, false) }
			val why = when {
				parent is KtFunctionLiteral -> "it is inside a lambda"
				parent is KtBinaryExpression && parent.operationToken in SHORT_CIRCUIT && inside(parent.right) ->
					"it runs only when the ${parent.operationReference.text} gets to it"
				parent is KtIfExpression && !inside(parent.condition) -> "it runs only when the if takes its branch"
				parent is KtWhenExpression && !inside(parent.subjectExpression) ->
					"it runs only when the when takes its branch"
				parent is KtWhileExpressionBase && inside(parent.condition) ->
					"it runs each time the loop tests its condition"
				parent is KtSafeQualifiedExpression && inside(parent.selectorExpression) ->
					"it runs only when the ?. finds a value"
				else -> null
			}
			if (why != null) {
				return why
			}
			node = parent
		}
		val start = value.textRange.startOffset
		val earlier = PsiTreeUtil.collectElementsOfType(around.root, KtCallExpression::class.java).firstOrNull { call ->
			call.textRange.endOffset <= start && around.values.none { PsiTreeUtil.isAncestor(it, call, false) }
		} ?: return null
		return "it comes after ${shown(earlier)}, which would then run later"
	}

	/** A call as a message names it: as written, with its receiver, where that is short, and otherwise its name. */
	private fun shown(call: KtCallExpression): String {
		val whole = (call.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression == call } ?: call
		val text = whole.text
		return if (text.length <= SHOWN_CALL && '\n' !in text) text else "${call.calleeExpression?.text}(...)"
	}

	/**
	 * The name of the local a value read more than once goes into: the property's or the call's that gives it, as
	 * written, `detailed` for `ledger.detailed()`, but without `get`, `is`, `mutable`, `Of` or `OrNull`, `first` for
	 * `firstOrNull()` and `list` for `mutableListOf()`; otherwise as [hoistedName] names it.
	 */
	fun valueName(value: KtExpression): String {
		val plain = KtPsiUtil.safeDeparenthesize(value)
		val selector = (plain as? KtQualifiedExpression)?.selectorExpression ?: plain
		val own = when (selector) {
			is KtNameReferenceExpression -> selector.getReferencedName()
			is KtCallExpression -> (selector.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
				?.let(::withoutAffixes)
			else -> null
		}
		return own?.replaceFirstChar { it.lowercaseChar() } ?: hoistedName(value)
	}

	/**
	 * A function's name as its value's: without `get`, `is`, `mutable`, `Of` or `OrNull`; null for one that names no
	 * value then, such as `getOrElse`.
	 */
	private fun withoutAffixes(name: String): String? {
		var base = name.removeSuffix("OrNull").removeSuffix("Of")
		for (prefix in listOf("get", "is", "mutable")) {
			if (base.length > prefix.length && base.startsWith(prefix) && base[prefix.length].isUpperCase()) {
				base = base.substring(prefix.length)
			}
		}
		return base.takeUnless { it.isEmpty() || it.startsWith("Or") && it.length > 2 && it[2].isUpperCase() }
	}

	/**
	 * The name of a hoisted value's local where the value has none of its own: the parameter's that it is passed to,
	 * or one from its type; see [typeName].
	 */
	fun hoistedName(value: KtExpression): String = with(unit.session) {
		parameterName(value) ?: typeName(value.expressionType)
	}

	/**
	 * The name of the parameter a value is passed to; null for a value that is no argument, a vararg's, and one of a
	 * compiled Java class that keeps no names, which Kotlin calls `p0`, `p1` and so on.
	 *
	 * @param ownOnly whether only a function of the plugin's or the client's names it, not one of the Kotlin library,
	 *     whose names say only what it takes, `element`
	 */
	private fun parameterName(value: KtExpression, ownOnly: Boolean = false): String? = with(unit.session) {
		val argument = value.parent as? KtValueArgument ?: return null
		val call = PsiTreeUtil.getParentOfType(argument, KtCallExpression::class.java) ?: return null
		val resolved = call.resolveToCall()?.successfulFunctionCallOrNull() ?: return null
		if (ownOnly && isLibrary(resolved.symbol)) {
			return null
		}
		val parameter = resolved.valueArgumentMapping[argument.getArgumentExpression()]?.symbol ?: return null
		parameter.name.asString().takeUnless { parameter.isVararg || UNNAMED_PARAMETER.matches(it) }
	}

	/**
	 * A name for a local from its value's type: `text` for a string, and for a class of the plugin or the client its
	 * name, `label` for a `Label`; `value` otherwise.
	 */
	fun typeName(type: KaType?): String = with(unit.session) {
		val symbol = type?.expandedSymbol
		when {
			type != null && types.isString(type) -> "text"
			symbol != null && (unit.isPluginType(symbol) || api.isApi(symbol)) ->
				symbol.classId?.shortClassName?.asString()?.replaceFirstChar { it.lowercaseChar() } ?: "value"
			else -> "value"
		}
	}

	/**
	 * `if (c) a else b` as `c and a or b`, which Lua evaluates the same way only when `a` can be neither false nor
	 * nil. Another `if` gives its value only to a `return`, a local or an assignment.
	 */
	private fun conditional(conditional: KtIfExpression): Expression {
		val condition = conditional.condition ?: throw Rejection(conditional, EMPTY)
		val then = conditional.then
		val otherwise = conditional.`else`
		if (then == null || otherwise == null) {
			throw Rejection(conditional, "This if has no else, so it gives no value; give it one, or make it a"
				+ " statement of its own.")
		}
		if (!isSafeConditional(conditional)) {
			return hoisted(conditional)
		}
		val test = Expression.binary(this.condition(condition), BinaryOperator.AND, expression(then))
		return Expression.binary(test, BinaryOperator.OR, expression(otherwise))
	}

	/**
	 * An expression that decides a branch or a loop. An optional dependency's `isLoaded()` is its global itself there,
	 * which Lua tests for nil as it tests the condition, `if gauges then`, as it is in its negation and beside
	 * another condition; elsewhere the check is a boolean, `gauges ~= nil`.
	 */
	fun condition(condition: KtExpression): Expression {
		val inner = KtPsiUtil.safeDeparenthesize(condition)
		unit.optionals.loaded(inner)?.let { alias ->
			unit.useGlobal(alias)
			return Expression.name(alias)
		}
		val negated = (inner as? KtPrefixExpression)?.takeIf { it.operationToken == KtTokens.EXCL }?.baseExpression
		if (negated != null && unit.optionals.loaded(negated) != null) {
			return Expression.Unary(UnaryOperator.NOT, this.condition(negated))
		}
		val left = (inner as? KtBinaryExpression)?.left
		val right = (inner as? KtBinaryExpression)?.right
		val token = (inner as? KtBinaryExpression)?.operationToken
		if (left != null && right != null && (token == KtTokens.ANDAND || token == KtTokens.OROR) &&
			(isLoadedCheck(left) || isLoadedCheck(right))) {
			return Expression.binary(this.condition(left),
				if (token == KtTokens.ANDAND) BinaryOperator.AND else BinaryOperator.OR, this.condition(right))
		}
		return expression(condition)
	}

	/** Whether an expression is an optional dependency's check, or its negation. */
	private fun isLoadedCheck(expression: KtExpression): Boolean {
		val inner = KtPsiUtil.safeDeparenthesize(expression)
		val negated = (inner as? KtPrefixExpression)?.takeIf { it.operationToken == KtTokens.EXCL }?.baseExpression
		return unit.optionals.loaded(inner) != null || (negated != null && unit.optionals.loaded(negated) != null)
	}

	/**
	 * Whether an `if` has single values that are never false or nil, and that need no statements before them, so that
	 * Lua can say it as `c and a or b`.
	 */
	private fun isSafeConditional(conditional: KtIfExpression): Boolean {
		val then = conditional.then
		val otherwise = conditional.`else`
		val type = then?.let { with(unit.session) { it.expressionType } }
		return then != null && then !is KtBlockExpression && otherwise != null && otherwise !is KtBlockExpression &&
			otherwise !is KtIfExpression && type != null && !types.isBoolean(type) && !types.isNullable(type) &&
			!needsStatements(then) && !needsStatements(otherwise)
	}

	/**
	 * Whether Lua makes a branch's value, or a value inside it, with statements, which can run before the code around
	 * them but not before a branch's: a chain, a builder, `buildString`, `run`, `use`, a `when`, a `try`, a scope
	 * function whose code cannot be read in place, or an `if` Lua cannot say as one expression. The code of lambdas
	 * inside the branch is left out, as it runs where the lambda does.
	 */
	private fun needsStatements(branch: KtExpression): Boolean =
		PsiTreeUtil.collectElementsOfType(branch, KtExpression::class.java).any { value ->
			val enclosing = PsiTreeUtil.getParentOfType(value, KtFunction::class.java, KtClassOrObject::class.java)
			val inLambda = enclosing != null && PsiTreeUtil.isAncestor(branch, enclosing, false)
			!inLambda && (value is KtWhenExpression || value is KtTryExpression ||
				value is KtIfExpression && value.`else` != null && !isSafeConditional(value) ||
				chains.chain(value) != null || builders.builder(value) != null || strings.isBuildString(value) ||
				isRun(value) || isUse(value) || scopes.scope(value)?.let(scopes::readsInPlace) == false)
		}

	private fun constant(constant: KtConstantExpression): Expression = when (constant.node.elementType) {
		KtNodeTypes.NULL -> Expression.Nil()
		KtNodeTypes.BOOLEAN_CONSTANT -> Expression.BooleanLiteral(constant.text == "true")
		KtNodeTypes.INTEGER_CONSTANT -> integer(constant)
		KtNodeTypes.CHARACTER_CONSTANT -> chars.literal(constant)
		// Lua's numbers with a point or an exponent are floats, as Kotlin's Double and Float are
		KtNodeTypes.FLOAT_CONSTANT -> Expression.NumberLiteral(constant.text.replace("_", "").trimEnd('f', 'F'))
		else -> throw Rejection.notYet(constant, "This literal")
	}

	/** An integer literal, decimal or hexadecimal, as Lua writes it; Kotlin's underscores and suffixes go. */
	private fun integer(constant: KtConstantExpression): Expression {
		val text = constant.text.replace("_", "").trimEnd('L', 'l', 'u', 'U')
		return when {
			text.startsWith("0x") || text.startsWith("0X") -> Expression.NumberLiteral(text)
			text.startsWith("0b") || text.startsWith("0B") -> Expression.integer(text.substring(2).toLong(2))
			else -> Expression.integer(text.toLong())
		}
	}

	/**
	 * A string literal, or a template as one call of the client's `formatStr`: `"Coins: $coins"` is
	 * `formatStr("Coins: {}", coins)`.
	 */
	private fun string(template: KtStringTemplateExpression): Expression {
		if (!template.hasInterpolation()) {
			val text = template.entries.joinToString("") { literalText(it) }
			// A raw string of several lines reads best as one in Lua too
			return Expression.StringLiteral(text, isRaw(template) && text.contains('\n'))
		}
		return strings.concatenation(template)
	}

	/** Whether a string is written raw, between triple quotes. */
	fun isRaw(template: KtStringTemplateExpression): Boolean = template.text.startsWith("\"\"\"")

	fun literalText(entry: KtStringTemplateEntry): String = when (entry) {
		is KtEscapeStringTemplateEntry -> entry.unescapedValue
		is KtLiteralStringTemplateEntry -> entry.text
		else -> throw IllegalStateException("not literal text: ${entry.text}")
	}

	/**
	 * `this` and `super`: the object a method runs on, `self`; `this@Outer` in an inner class, the object it belongs
	 * to.
	 */
	private fun self(where: KtInstanceExpressionWithLabel): Expression {
		aliasedReceiver(where)?.let { return it }
		if (!instance) {
			throw Rejection(where, if (owner != null && owner == unit.entry) "The plugin class becomes main.lua, whose"
				+ " members are locals with no object to pass around; pass what is needed instead of this."
				else "This code runs on no object, so there is no this.")
		}
		val label = where.getLabelName() ?: return Expression.name("self")
		var target: Expression = Expression.name("self")
		var current = owner
		while (current is KtClass && current.name != label && current.isInner()) {
			target = Expression.field(target, "outer")
			current = current.containingClassOrObject
		}
		if (current?.name != label) {
			throw Rejection.notYet(where, "this@$label here")
		}
		return target
	}

	/**
	 * `receiver?.member` as `receiver and receiver.member`, which gives nil for a nil receiver; the receiver is read
	 * twice (see [reused]), and must never be false.
	 */
	private fun safeCall(safe: KtSafeQualifiedExpression): Expression {
		val receiver = safe.receiverExpression
		val type = with(unit.session) { receiver.expressionType }
		if (type == null || types.isBoolean(type)) {
			throw Rejection(safe, "Lua's and would take a false here for a nil; test the value with if instead.")
		}
		return Expression.binary(reused(receiver, NIL_TEST), BinaryOperator.AND, qualified(safe))
	}

	/**
	 * `receiver?.let { x -> ... }` and the other scope functions as a statement: the lambda's code when the receiver is
	 * not nil, `local x = receiver` first for `let`; `receiver?.member(...)` runs the call when the receiver, read
	 * twice (see [reused]), is not nil.
	 */
	private fun safeStatement(safe: KtSafeQualifiedExpression): List<Statement> {
		scopes.safeScope(safe)?.let { return scopes.safeEffect(it) }
		val receiver = reused(safe.receiverExpression, NIL_TEST)
		val present = Expression.binary(receiver, BinaryOperator.NOT_EQUAL, Expression.Nil())
		val lowered = qualified(safe)
		if (lowered !is Expression.Call && lowered !is Expression.MethodCall) {
			throw Rejection(safe, "In Lua a statement is a call or an assignment; this expression's value would go"
				+ " nowhere, so remove it or use it.")
		}
		return listOf(Statement.ifThen(present, Block.of(Statement.CallStatement(lowered))))
	}

	/** `receiver.member` or `receiver.member(...)`. */
	private fun qualified(qualified: KtQualifiedExpression): Expression {
		val receiver = qualified.receiverExpression
		if (KtPsiUtil.safeDeparenthesize(receiver) is KtClassLiteralExpression || isJavaClass(qualified)) {
			throw Rejection(qualified, NO_CLASSES)
		}
		// ground.height() or span.low: the local its component was read into
		val selector = qualified.selectorExpression
		val member = (selector as? KtNameReferenceExpression)?.getReferencedName()
			?: ((selector as? KtCallExpression)?.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
		member?.let { returnValues.component(receiver, it, qualified) }?.let { return it }
		return when (val selector = qualified.selectorExpression) {
			is KtCallExpression -> call(selector, receiver, qualified)
			is KtNameReferenceExpression -> reference(selector, receiver, qualified)
			else -> throw Rejection.notYet(qualified, "This kind of member access")
		}
	}

	/**
	 * A name: a local or parameter, a property of the plugin class, an object or a class, a field of the RS3 bindings,
	 * an enum's constant, or a property of the Kotlin library.
	 *
	 * @param receiver the expression before the dot, or null when there is none
	 * @param where the whole expression, `receiver.name`, where a problem with it is reported
	 */
	private fun reference(reference: KtNameReferenceExpression, receiver: KtExpression?,
		where: KtExpression = reference): Expression =
		with(unit.session) {
			when (val symbol = reference.resolveSymbol()) {
				is KaLocalVariableSymbol, is KaValueParameterSymbol -> {
					if (unit.entryAlias(declarationOf(symbol, reference)) != null) {
						throw Rejection(reference, MapEntries.ENTRY_PARTS)
					}
					if (returnValues.isSplit(reference)) {
						val example = returnValues.componentExample(reference)?.let { ", such as $it" }.orEmpty()
						throw Rejection(reference, "The values a Lua function returns exist only at its call; read them"
							+ " through ${reference.getReferencedName()}'s components$example.")
					}
					Expression.name(unit.localName(declarationOf(symbol, reference), reference.getReferencedName()))
				}
				is KaJavaFieldSymbol -> {
					val owner = api.owner(symbol)
					if (owner == null || !api.isApi(owner)) {
						throw Rejection.notYet(where, "Reading ${reference.getReferencedName()}")
					}
					api.field(where, symbol, owner, if (symbol.isStatic) null else receiverOrSelf(receiver, where))
				}
				is KaEnumEntrySymbol -> {
					val enumClass = symbol.callableId?.classId?.let { findClass(it) }
						?: throw IllegalStateException("an enum constant outside its enum: ${reference.text}")
					if (api.isApi(enumClass)) {
						api.enumConstant(where, symbol, enumClass)
					} else {
						Expression.field(unit.moduleReference(enumClass), symbol.name.asString())
					}
				}
				is KaPropertySymbol -> property(reference, symbol, receiver, where)
				is KaClassSymbol -> {
					if (symbol.classKind != KaClassKind.OBJECT || !unit.isPluginType(symbol)) {
						throw Rejection.notYet(where, "Using ${reference.getReferencedName()} as a value")
					}
					unit.moduleReference(symbol)
				}
				else -> throw Rejection.notYet(where, "Using ${reference.getReferencedName()} here")
			}
		}

	/** A Kotlin property: of the Kotlin library, or of the plugin's own classes and objects. */
	private fun property(reference: KtNameReferenceExpression, symbol: KaPropertySymbol, receiver: KtExpression?,
		where: KtExpression): Expression = with(unit.session) {
		entryPart(receiver, symbol)?.let { return it }
		enumProperty(where, symbol, receiver)?.let { return it }
		val thrown = receiver?.expressionType?.expandedSymbol
		if (receiver != null && thrown != null && exceptions.isThrowable(thrown)) {
			// A Lua error is only its value, which is the message
			if (symbol.name.asString() != "message" && symbol.name.asString() != "localizedMessage") {
				throw Rejection(where, "A Lua error is only its value; read it with ${receiver.text}.message.")
			}
			return expression(receiver)
		}
		if (isLibrary(symbol)) {
			return library.property(where, symbol, libraryReceiver(receiver, reference))
		}
		val classId = symbol.callableId?.classId
		val declaration = symbol.psi
		val name = reference.getReferencedName()
		if (receiver == null && declaration is KtParameter && inInitializer(reference)) {
			// In an init block or a property's value, the name of a constructor's property is its parameter
			return Expression.name(unit.localName(declaration, name))
		}
		if (declaration is KtProperty && declaration.receiverTypeReference != null) {
			return extensionRead(where, declaration, symbol, receiver, reference)
		}
		if (declaration is KtProperty && declaration.parent is KtFile) {
			return topLevelMember(where, declaration, symbol)
		}
		val ownerClass = api.owner(symbol) ?: throw Rejection.notYet(where, "Reading $name")
		val ownerDeclaration = ownerClass.psi as? KtClassOrObject
		val entry = unit.entry
		if (entry != null && (ownerDeclaration == entry || ownerDeclaration?.parent?.parent == entry)) {
			// The plugin class's properties, and its companion object's, are locals of main.lua
			if (declaration is KtProperty && unit.isSettingsProperty(declaration)) {
				val settings = declaration.symbol.returnType.expandedSymbol as KaClassSymbol
				return unit.moduleReference(settings)
			}
			return Expression.name(unit.names.luaName(name))
		}
		if (ownerClass.classKind == KaClassKind.OBJECT || ownerClass.classKind == KaClassKind.COMPANION_OBJECT) {
			val local = declaration is KtProperty && declaration.isPrivateInLua() &&
				declaration.containingFile == unit.file
			if (local) {
				return Expression.name(unit.names.luaName(name))
			}
			return Expression.field(staticTable(ownerClass), unit.memberName(symbol))
		}
		return Expression.field(receiverOrSelf(receiver, where, ownerClass), unit.memberName(symbol))
	}

	/**
	 * `entry.key` and `entry.value` of a lambda's parameter that is a map's entry: the key and the value of the loop
	 * the lambda's code is opened in; see [FileLowering.aliasEntry]. Null for any other property.
	 */
	private fun entryPart(receiver: KtExpression?, symbol: KaPropertySymbol): Expression? = with(unit.session) {
		val name = receiver as? KtNameReferenceExpression ?: return null
		val declaration = name.resolveSymbol()?.let { declarationOf(it, name) }
		val (key, value) = unit.entryAlias(declaration) ?: return null
		when (symbol.name.asString()) {
			"key" -> Expression.name(key)
			"value" -> Expression.name(value)
			else -> throw Rejection(receiver, MapEntries.ENTRY_PARTS)
		}
	}

	/**
	 * The members Kotlin gives an enum of the plugin's: `Mode.entries`, its constants in a list, and a constant's
	 * `name`, which in Lua is a simple enum's constant itself, and an object enum's field, as its `ordinal` is.
	 */
	private fun enumProperty(where: KtExpression, symbol: KaPropertySymbol, receiver: KtExpression?): Expression? =
		with(unit.session) {
			val name = symbol.name.asString()
			val owner = symbol.callableId?.classId?.let { findClass(it) }
			val pluginEnum = owner != null && unit.isPluginType(owner) && owner.classKind == KaClassKind.ENUM_CLASS
			if (name == "entries" && pluginEnum) {
				return constants(owner)
			}
			if ((symbol.containingSymbol as? KaClassSymbol)?.classId != StandardClassIds.Enum) {
				return null
			}
			// A client enum's value is the client's number
			val apiEnum = receiver?.expressionType?.expandedSymbol?.takeIf {
				it.classKind == KaClassKind.ENUM_CLASS && api.isApi(it)
			}
			if (apiEnum != null) {
				if (name != "name") {
					throw Rejection(where, "A client enum's value is the client's number, which has no $name; compare"
						+ " it with the enum's constants instead.")
				}
				return api.enumName(where, expression(receiver), apiEnum)
			}
			// Inside an object enum, its own name and ordinal
			val enumType = if (receiver == null) ownEnum() else pluginEnum(receiver.expressionType)
			if (enumType == null || name != "name" && name != "ordinal") {
				return null
			}
			if (unit.isObjectEnum(enumType)) {
				return Expression.field(receiverOrSelf(receiver, where), name)
			}
			if (name == "ordinal") {
				throw Rejection(where, "In Lua a simple enum's constants are the strings of their names, which have no"
					+ " ordinal; give the enum a property for what the ordinal stands for.")
			}
			expression(receiver!!)
		}

	/** The enum whose code this is, a constant's body among it; null outside an enum. */
	private fun ownEnum(): KaClassSymbol? = with(unit.session) {
		val declaration = (owner as? KtEnumEntry)?.containingClassOrObject ?: owner
		(declaration as? KtClass)?.takeIf { it.isEnum() }?.classSymbol
	}

	/** A constant of an enum of the plugin's as text: a simple enum's is its string, an object enum's its name. */
	fun enumText(value: KtExpression): Expression? = with(unit.session) {
		val type = pluginEnum(value.expressionType) ?: return null
		if (unit.isObjectEnum(type)) Expression.field(expression(value), "name") else expression(value)
	}

	/** The constants of an enum of the plugin's, in order, as a list: `{ Volume.QUIET, Volume.LOUD }`. */
	private fun constants(enumClass: KaClassSymbol): Expression {
		val table = unit.moduleReference(enumClass)
		val entries = (enumClass.psi as? KtClass)?.declarations?.filterIsInstance<KtEnumEntry>() ?: listOf()
		return Expression.Table(entries.map { Expression.TableField.Positional(Expression.field(table, it.name!!)) })
	}

	/** The enum of the plugin's that a type is, or null for any other type. */
	fun pluginEnum(type: KaType?): KaClassSymbol? = with(unit.session) {
		val symbol = type?.expandedSymbol ?: return null
		if (symbol.classKind == KaClassKind.ENUM_CLASS && unit.isPluginType(symbol)) symbol else null
	}

	/** Whether code runs while the constructor does: in an `init` block, or in the value of a class's property. */
	private fun inInitializer(element: PsiElement): Boolean {
		for (parent in generateSequence(element.parent) { it.parent }) {
			when {
				parent is KtAnonymousInitializer -> return true
				parent is KtProperty && parent.parent is KtClassBody -> return true
				parent is KtNamedFunction || parent is KtPropertyAccessor || parent is KtClassOrObject -> return false
			}
		}
		return false
	}

	/** The table an object's members belong to: its own, or for a companion object its class's. */
	fun staticTable(ownerClass: KaClassSymbol): Expression = with(unit.session) {
		if (ownerClass.classKind == KaClassKind.COMPANION_OBJECT) {
			unit.moduleReference(findClass(ownerClass.classId!!.outerClassId!!)!!)
		} else {
			unit.moduleReference(ownerClass)
		}
	}

	/**
	 * The object a member is read on: the receiver as written, or `self` for a member of the object itself. A member of
	 * a class around an inner class is read on the object the inner one belongs to, `self.outer`.
	 *
	 * @param memberOwner the class that declares the member, or null when it is the code's own class
	 */
	fun receiverOrSelf(receiver: KtExpression?, where: KtElement, memberOwner: KaClassSymbol? = null): Expression {
		if (receiver != null) {
			return expression(receiver)
		}
		implicitReceiver(where)?.let { return it }
		if (!instance) {
			throw Rejection.notYet(where, "Reading a member of an object here")
		}
		var target: Expression = Expression.name("self")
		var current = owner
		while (memberOwner != null && current is KtClass && current.isInner() && !isOrExtends(current, memberOwner)) {
			target = Expression.field(target, "outer")
			current = current.containingClassOrObject
		}
		if (memberOwner != null && current is KtObjectDeclaration && current.isObjectLiteral() &&
			!isOrExtends(current, memberOwner)) {
			throw Rejection.notYet(where, "Reading a member of the class around an object expression")
		}
		return target
	}

	/**
	 * The receiver a member named without one is read on, when that is a scope function's or an extension function's
	 * `this`, `apply { SetPos(1, 2) }`: its Lua name and its type; null when it is the class's own object, or there
	 * is none.
	 */
	fun unnamedReceiver(member: KtElement, extension: Boolean = false): Receiver.Unnamed? = with(unit.session) {
		val call = member.resolveToCall()?.successfulCallOrNull<KaCallableMemberCall<*, *>>() ?: return null
		val applied = call.partiallyAppliedSymbol
		// An extension function's own receiver, or the object a member is read on
		val receivers = if (extension) listOf(applied.extensionReceiver)
		else listOf(applied.dispatchReceiver, applied.extensionReceiver)
		receivers.filterIsInstance<KaImplicitReceiverValue>().firstNotNullOfOrNull { receiver ->
			val owner = (receiver.symbol as? KaReceiverParameterSymbol)?.owningCallableSymbol?.psi
			unit.receiverAlias(owner)?.let { Receiver.Unnamed(it, receiver.type) }
		}
	}

	private fun implicitReceiver(member: KtElement, extension: Boolean = false): Expression? =
		unnamedReceiver(member, extension)?.lua

	/**
	 * The receiver of a member of the Kotlin library: the one written before the dot, or the one the member is named
	 * on without it; null for a function outside any class, such as `listOf`.
	 */
	private fun libraryReceiver(written: KtExpression?, member: KtElement): Receiver? =
		written?.let(Receiver::Written) ?: unnamedReceiver(member)

	/** `this` that names a scope function's or an extension function's receiver: its Lua name; null for another. */
	private fun aliasedReceiver(where: KtInstanceExpressionWithLabel): Expression? = with(unit.session) {
		val owner = when (val symbol = where.resolveSymbol()) {
			is KaReceiverParameterSymbol -> symbol.owningCallableSymbol.psi
			is KaFunctionSymbol -> symbol.psi
			else -> return null
		}
		unit.receiverAlias(owner)
	}

	private fun isOrExtends(declaration: KtClassOrObject, type: KaClassSymbol): Boolean = with(unit.session) {
		val symbol = declaration.classSymbol ?: return false
		symbol == type || symbol.isSubClassOf(type)
	}

	/**
	 * A call: of a constructor, a function of the plugin, a member of the RS3 bindings, or of the Kotlin library.
	 *
	 * @param where the whole expression, `receiver.name(...)`, where a problem with it is reported
	 */
	private fun call(
		call: KtCallExpression,
		receiver: KtExpression?,
		where: KtExpression = call,
	): Expression = with(unit.session) {
		val resolved = call.resolveToCall()?.successfulFunctionCallOrNull()
			?: throw Rejection(where, "This call does not resolve to one function.")
		val symbol = resolved.symbol
		checkClientHasIt(where, symbol, receiver)
		val invoked = if (resolved is KaImplicitInvokeCall) invokedValue(call, receiver, where) else null
		if (symbol is KaConstructorSymbol) {
			// A type alias's constructor, such as HashMap's, belongs to no class; it makes the class the alias names
			val type = symbol.containingClassId?.let { findClass(it) } ?: symbol.returnType.expandedSymbol
				?: throw IllegalStateException("a constructor outside a class: ${call.text}")
			if (api.isApi(type)) {
				return api.construct(where, symbol, type, arguments(resolved, call))
			}
			if (unit.isEntryClass(type)) {
				throw Rejection(where, "The client creates the plugin class, once, when the plugin starts; use its"
					+ " members directly instead of creating another.")
			}
			if (unit.annotations.has(type, Annotations.LUA_RETURN_VALUES)) {
				throw Rejection(where, "${type.classId?.shortClassName} stands for several Lua values, which only a"
					+ " return makes; return it as it is made, or pass its values one by one.")
			}
			if (unit.isPluginType(type) && (type.psi as? KtClass)?.isPlainData() == true) {
				return plainData(resolved, call)
			}
			if (!unit.isPluginType(type) && exceptions.isThrowable(type)) {
				return exceptions.exceptionValue(call, type)
			}
			if (unit.isPluginType(type)) {
				val arguments = arguments(resolved, call)
				// An inner class's object belongs to one of the class around it, which its constructor takes first
				val outer = if ((type.psi as? KtClass)?.isInner() == true) {
					listOf(receiverOrSelf(receiver, where, type.classId?.outerClassId?.let { findClass(it) }))
				} else {
					listOf()
				}
				return Expression.Call(Expression.field(unit.moduleReference(type), "new"), outer + arguments)
			}
			return library.construct(where, type.classId!!, libraryArguments(resolved, call))
		}
		if (symbol is KaSamConstructorSymbol) {
			// Predicate { ... } makes a functional interface's value of a lambda, which in Lua is the function itself
			return expression(argumentTrees(resolved, call).single())
		}
		enumCall(symbol, receiver, resolved, call)?.let { return it }
		if (receiver != null && isFunctionalMethod(symbol)) {
			// A value of a functional interface of the library is a Lua function, which has no methods to call
			return Expression.Call(expression(receiver), arguments(resolved, call))
		}
		if (isFunctionInvoke(symbol)) {
			// A value of a function type, called: f(x), holder.f(x) or f.invoke(x)
			val function = invoked ?: expression(receiver ?: call.calleeExpression ?: throw Rejection(call, EMPTY))
			return Expression.Call(function, arguments(resolved, call))
		}
		if (preconditions.isNotNull(call)) {
			return preconditions.notNullValue(call)
		}
		scopes.scope(call, receiver, where)?.let { return scopes.value(it) }
		scopes.test(call, receiver, where)?.let { test ->
			return scopes.testValue(test) ?: hoistedInto(where, hoistedName(where), STATEMENTS_FIRST.format(
				test.function)) { name -> scopes.testLocal(name, test).map(Block.Entry::of) }
		}
		if (receiver == null && isRun(call)) {
			return hoistedInto(call, hoistedName(call), STATEMENTS_FIRST.format("run")) { name ->
				val code = runValue(call) { Statement.assignment(Expression.name(name), it) }
				(listOf(Statement.Local(listOf(name), listOf())) + code).map(Block.Entry::of)
			}
		}
		builders.builder(call)?.let { builder ->
			val kind = builders.holderName(builder)
			val preferred = parameterName(call, ownOnly = true)?.takeIf { isFree(it, call) } ?: kind
			return hoistedInto(call, preferred, STATEMENTS_FIRST.format(builder)) { name ->
				builders.built(call, name).map(Block.Entry::of)
			}
		}
		if (strings.isBuildString(call)) {
			// The pieces are put together before the statement, and joined where the text is read
			lateinit var pieces: String
			hoisted(call, STATEMENTS_FIRST.format("buildString")) {
				pieces = invent("text", call)
				strings.pieces(call, pieces).map(Block.Entry::of)
			}
			return strings.joined(pieces)
		}
		if (api.owner(symbol)?.classId?.asFqNameString() == LuaPatterns.CLASS) {
			if (symbol.callableId?.callableName?.asString() == "gmatch") {
				throw Rejection(where, LuaPatterns.GMATCH_OUTSIDE_LOOP)
			}
			return LuaPatterns.call(symbol.callableId!!.callableName.asString(), arguments(resolved, call))
		}
		if (isLibrary(symbol)) {
			return library.call(where, symbol, libraryReceiver(receiver, call), libraryArguments(resolved, call))
		}
		if (symbol !is KaNamedFunctionSymbol) {
			throw Rejection.notYet(where, "Calling this")
		}
		if (invoked != null && symbol.isExtension) {
			throw Rejection.notYet(where, "Calling a value through an extension's invoke")
		}
		topLevelFunction(symbol)?.let { declaration ->
			val callee = topLevelMember(where, declaration, symbol)
			if (!symbol.isExtension) {
				return Expression.Call(callee, arguments(resolved, call))
			}
			// An extension function takes its receiver first
			val extended = receiver?.let(::expression) ?: implicitReceiver(where, extension = true)
				?: throw Rejection.notYet(where, "Calling an extension function on this receiver")
			return Expression.Call(callee, listOf(extended) + arguments(resolved, call))
		}
		if (symbol.origin == KaSymbolOrigin.SOURCE_MEMBER_GENERATED) {
			throw Rejection(where, "Kotlin generates ${symbol.name}() for a data class, but its Lua table has no such"
				+ " function; use the class's properties instead.")
		}
		val ownerClass = api.owner(symbol) ?: throw Rejection.notYet(where, "Calling a function outside a class")
		val name = symbol.name.asString()
		if (unit.isEntryClass(ownerClass) && (name == "startUp" || name == "shutDown") &&
			symbol.valueParameters.isEmpty()) {
			val moment = if (name == "startUp") "starts" else "stops"
			throw Rejection(where, "$name() runs once, when the plugin $moment; move what it shares into a function of"
				+ " its own and call that.")
		}
		val arguments = arguments(resolved, call)
		if (api.isApi(ownerClass)) {
			checkSubscription(symbol, receiver, where)
			val target = if (symbol.isStatic) null else receiverOrSelf(receiver, where)
			return api.call(where, symbol, ownerClass, target, arguments)
		}
		if (!unit.isPluginType(ownerClass)) {
			throw Rejection.notYet(where, "Calling ${ownerClass.classId?.asFqNameString()}.${symbol.name}")
		}
		if (symbol.isExtension) {
			return extensionCall(where, symbol, ownerClass, receiver, arguments)
		}
		return pluginCall(where, symbol, ownerClass, receiver, arguments, invoked)
	}

	/**
	 * The value an implicit `invoke` is called on: `f` for `f(x)`, and `holder.f` for `holder.f(x)`, whose receiver is
	 * `holder` rather than that value.
	 */
	private fun invokedValue(call: KtCallExpression, receiver: KtExpression?, where: KtExpression): Expression {
		val callee = call.calleeExpression ?: throw Rejection(call, EMPTY)
		if (receiver == null) {
			return expression(callee)
		}
		val name = callee as? KtNameReferenceExpression ?: throw Rejection.notYet(where, "Calling this value")
		return reference(name, receiver, where)
	}

	/**
	 * An extension property of the plugin's, read: its getter, a function of its object or file, called on the
	 * receiver, as an extension function is, `Texts.initial(name)`.
	 */
	private fun extensionRead(where: KtExpression, declaration: KtProperty, symbol: KaPropertySymbol,
		receiver: KtExpression?, reference: KtNameReferenceExpression): Expression = with(unit.session) {
		val getter = if (declaration.parent is KtFile) {
			topLevelMember(where, declaration, symbol)
		} else {
			val owner = api.owner(symbol) ?: throw Rejection.notYet(where, "Reading this extension property")
			val name = unit.memberName(symbol)
			// The plugin class's members are all locals of main.lua
			val local = declaration.isPrivateInLua() || unit.entry != null && owner.psi == unit.entry
			if (local && declaration.containingFile == unit.file) {
				Expression.name(unit.names.luaName(name))
			} else {
				Expression.field(staticTable(owner), name)
			}
		}
		val extended = receiver?.let(::expression) ?: implicitReceiver(reference, extension = true)
			?: throw Rejection.notYet(where, "Reading an extension property on this receiver")
		Expression.Call(getter, listOf(extended))
	}

	/** The declaration of a top-level function of the plugin's sources, or null for any other function. */
	fun topLevelFunction(function: KaCallableSymbol): KtNamedFunction? =
		(function.psi as? KtNamedFunction)?.takeIf { it.parent is KtFile }

	/**
	 * A top-level function or property of the plugin's: a local of the chunk of a file with a class, or of a file of
	 * top-level declarations when it is private; otherwise a member of the module of such a file, `Texts.shout`, which
	 * this file requires when it is another's.
	 */
	fun topLevelMember(where: KtExpression, declaration: KtCallableDeclaration, symbol: KaCallableSymbol): Expression {
		val declaring = declaration.containingKtFile
		val local = Expression.name(unit.names.luaName(unit.memberName(symbol)))
		if (!unit.isTopLevelFile(declaring)) {
			// The class's file reports one that is not private, which has no module to go in
			if (declaring == unit.file) {
				return local
			}
			throw Rejection.notYet(where, "Using a top-level function or property of another file with a class")
		}
		if (declaring == unit.file && declaration.isPrivateInLua()) {
			return local
		}
		return Expression.field(unit.topLevelModule(declaring), unit.memberName(symbol))
	}

	/**
	 * A call of an extension function of the plugin's, which takes its receiver first: the one written before the dot,
	 * or the receiver around the call, `Texts.shout(name)`.
	 */
	private fun extensionCall(call: KtExpression, function: KaNamedFunctionSymbol, ownerClass: KaClassSymbol,
		receiver: KtExpression?, arguments: List<Expression>): Expression = with(unit.session) {
		val extended = receiver?.let(::expression) ?: implicitReceiver(call, extension = true)
			?: throw Rejection.notYet(call, "Calling an extension function on this receiver")
		val name = unit.memberName(function)
		val declaration = function.psi
		val isPrivate = declaration is KtNamedFunction && declaration.isPrivateInLua() &&
			declaration.containingFile == unit.file
		val callee = when {
			(ownerClass.psi as? KtClassOrObject) == unit.entry || isPrivate -> Expression.name(unit.names.luaName(name))
			else -> Expression.field(staticTable(ownerClass), name)
		}
		Expression.Call(callee, listOf(extended) + arguments)
	}

	/**
	 * Rejects what the client has nothing for, a clock and threads, in a call or in the calls its receiver is made of;
	 * see [ClientLimits].
	 */
	private fun checkClientHasIt(where: KtExpression, symbol: KaCallableSymbol, receiver: KtExpression?): Unit =
		with(unit.session) {
			val inner = receiver as? KtCallExpression ?: (receiver as? KtDotQualifiedExpression)?.selectorExpression
				as? KtCallExpression
			val innerSymbol = inner?.resolveToCall()?.successfulFunctionCallOrNull()?.symbol
			if (receiver != null && innerSymbol != null) {
				checkClientHasIt(receiver, innerSymbol, (receiver as? KtDotQualifiedExpression)?.receiverExpression)
			}
			val id = symbol.callableId ?: return
			val owner = (symbol as? KaConstructorSymbol)?.containingClassId ?: id.classId
			val ownerName = owner?.asFqNameString() ?: id.packageName.asString()
			val member = if (symbol is KaConstructorSymbol) null else id.callableName.asString()
			if (ClientLimits.readsClock(ownerName, member)) {
				throw Rejection(where, ClientLimits.NO_CLOCK)
			}
			val supertypes = owner?.let { findClass(it) }?.defaultType?.allSupertypes.orEmpty()
				.mapNotNull { it.expandedSymbol?.classId?.asFqNameString() }
			if (ClientLimits.isConcurrent(ownerName) || supertypes.any { it in ClientLimits.CONCURRENT_SUPERTYPES }) {
				throw Rejection(where, ClientLimits.NO_THREADS)
			}
		}

	/**
	 * A data class of properties alone is a plain table, built where the constructor is called:
	 * `Summary(seen, level)` is `{ seen = seen, level = level }`.
	 */
	private fun plainData(resolved: KaFunctionCall<*>, call: KtCallExpression): Expression = with(unit.session) {
		val values = argumentTrees(resolved, call)
		val fields = resolved.symbol.valueParameters.zip(values).map { (parameter, value) ->
			val name = parameter.generatedPrimaryConstructorProperty?.let(unit::memberName) ?: parameter.name.asString()
			Expression.TableField.Named(name, expression(value))
		}
		Expression.Table(fields)
	}

	/**
	 * The functions Kotlin gives a simple enum of the plugin's: `Mode.valueOf(name)`, the constant of that name, which
	 * in Lua is `Mode[name]`, nil for none; `Mode.values()`, its constants in a list; and a constant's `toString()`,
	 * the constant itself.
	 */
	private fun enumCall(function: KaCallableSymbol, receiver: KtExpression?, resolved: KaFunctionCall<*>,
		call: KtCallExpression): Expression? = with(unit.session) {
		val name = function.callableId?.callableName?.asString()
		val owner = function.callableId?.classId?.let { findClass(it) }
		if (owner != null && owner.classKind == KaClassKind.ENUM_CLASS && unit.isPluginType(owner)) {
			when (name) {
				"valueOf" -> return Expression.Index(unit.moduleReference(owner),
					expression(argumentTrees(resolved, call).single()))
				"values" -> return constants(owner)
			}
		}
		if (receiver != null && name == "toString") {
			enumText(receiver)?.let { return it }
		}
		// An object enum's constants compare by their place in the enum
		if (receiver != null && name == "compareTo" && pluginEnum(receiver.expressionType) != null) {
			val other = argumentTrees(resolved, call).single()
			return Expression.binary(Expression.field(expression(receiver), "ordinal"), BinaryOperator.SUBTRACT,
				Expression.field(expression(other), "ordinal"))
		}
		null
	}

	/** Whether a function is the one function of a functional interface of the library, such as `Runnable.run`. */
	private fun isFunctionalMethod(function: KaCallableSymbol): Boolean = with(unit.session) {
		val owner = api.owner(function) ?: return false
		val id = owner.classId?.asFqNameString() ?: return false
		id.startsWith("java.") && owner.classKind == KaClassKind.INTERFACE &&
			function.modality == KaSymbolModality.ABSTRACT &&
			owner.annotations.any { it.classId?.asFqNameString() == "java.lang.FunctionalInterface" }
	}

	/** Whether an expression reads `value.javaClass`, the class of a value at run time. */
	private fun isJavaClass(qualified: KtQualifiedExpression): Boolean = with(unit.session) {
		val selector = qualified.selectorExpression as? KtNameReferenceExpression ?: return false
		val id = (selector.resolveSymbol() as? KaPropertySymbol)?.callableId
		id?.packageName?.asString() == "kotlin.jvm" && id.callableName.asString() == "javaClass"
	}

	/** Whether a function's receiver is a value of the type itself, or `this` inside it, rather than of a subclass. */
	private fun isValueOf(receiver: KtExpression?, type: KaClassSymbol): Boolean = with(unit.session) {
		val receiverType = if (receiver == null || receiver is KtThisExpression) owner?.classSymbol
		else receiver.expressionType?.expandedSymbol
		receiverType == null || receiverType == type
	}

	/** Whether a function is a function type's `invoke`, which calling a value of that type calls. */
	private fun isFunctionInvoke(function: KaCallableSymbol): Boolean {
		val id = function.callableId ?: return false
		val owner = id.classId?.asFqNameString() ?: return false
		return id.callableName.asString() == "invoke" && FUNCTION_TYPE.matches(owner)
	}

	/** Whether a member belongs to the Kotlin or Java library, rather than to the plugin or the RS3 bindings. */
	fun isLibrary(member: KaCallableSymbol): Boolean {
		val packageName = member.callableId?.packageName?.asString() ?: return false
		return packageName == "kotlin" || packageName.startsWith("kotlin.") || packageName.startsWith("java.")
	}

	/**
	 * `Event.ChatMessage.Subscribe(...)`: subscribing to an event that moderation reviews, whose field says so.
	 * Unsubscribing is no use of the event of its own.
	 */
	private fun checkSubscription(method: KaNamedFunctionSymbol, receiver: KtExpression?, where: KtExpression): Unit =
		with(unit.session) {
			if (receiver == null || method.name.asString() != "Subscribe") {
				return
			}
			val name = (if (receiver is KtDotQualifiedExpression) receiver.selectorExpression else receiver)
				as? KtNameReferenceExpression ?: return
			val event = name.resolveSymbol() as? KaJavaFieldSymbol ?: return
			unit.reviews.check(event, "Subscribing to ${event.name.asString()}", where)
		}

	/**
	 * A call of a function of the plugin's own classes: a settings function of its module, a function of the plugin
	 * class as a local of main.lua, an object's function on its table, and a class's method on the object.
	 *
	 * @param invoked the value an implicit `invoke` is called on, which is not the receiver, or null for any other call
	 */
	private fun pluginCall(
		call: KtExpression,
		function: KaNamedFunctionSymbol,
		ownerClass: KaClassSymbol,
		receiver: KtExpression?,
		arguments: List<Expression>,
		invoked: Expression? = null,
	): Expression = with(unit.session) {
		val name = unit.memberName(function)
		val ownerDeclaration = ownerClass.psi as? KtClassOrObject
		val declaration = function.psi
		val isPrivate = declaration is KtNamedFunction && declaration.isPrivateInLua() &&
			declaration.containingFile == unit.file
		// The object the function is called on
		fun target(memberOwner: KaClassSymbol? = null) = invoked ?: receiverOrSelf(receiver, call, memberOwner)
		return when {
			// An object expression that is a Lua function has no methods to call
			ownerDeclaration is KtObjectDeclaration && ownerDeclaration.isObjectLiteral() &&
				objects.isFunction(ownerDeclaration) -> Expression.Call(target(), arguments)
			// super.f(): the superclass's function, called on this object
			receiver is KtSuperExpression ->
				Expression.Call(Expression.field(unit.moduleReference(ownerClass), name), listOf(self(receiver)) +
					arguments)
			// A value of a plugin's fun interface is a Lua function, which has no methods to call
			(ownerDeclaration as? KtClass)?.hasModifier(KtTokens.FUN_KEYWORD) == true &&
				(invoked != null || isValueOf(receiver, ownerClass)) -> {
				if (function.modality != KaSymbolModality.ABSTRACT) {
					val type = ownerClass.classId?.shortClassName
					throw Rejection(call, "A value of $type is a Lua function, which carries no methods; make"
						+ " ${function.name}() a function outside the interface that takes the $type.")
				}
				Expression.Call(target(ownerClass), arguments)
			}
			unit.annotations.has(ownerClass, Annotations.CONFIG_GROUP) ->
				Expression.Call(Expression.field(unit.moduleReference(ownerClass), name), arguments)
			ownerDeclaration != null && ownerDeclaration == unit.entry ->
				Expression.Call(Expression.name(unit.names.luaName(name)), arguments)
			ownerClass.classKind == KaClassKind.OBJECT || ownerClass.classKind == KaClassKind.COMPANION_OBJECT ->
				if (isPrivate) {
					Expression.Call(Expression.name(unit.names.luaName(name)), arguments)
				} else {
					Expression.Call(Expression.field(staticTable(ownerClass), name), arguments)
				}
			isPrivate -> Expression.Call(Expression.name(unit.names.luaName(name)), listOf(target(ownerClass)) +
				arguments)
			else -> Expression.MethodCall(target(ownerClass), name, arguments)
		}
	}

	/** A call's arguments in the order of the parameters they go to; a vararg's values one by one. */
	fun argumentTrees(call: KaFunctionCall<*>, where: KtCallExpression): List<KtExpression> {
		val byParameter = call.valueArgumentMapping.entries.groupBy({ it.value.symbol }, { it.key })
		val arguments = mutableListOf<KtExpression>()
		for (parameter in call.symbol.valueParameters) {
			val values = byParameter[parameter]
			if (values == null) {
				if (parameter.isVararg) {
					continue
				}
				throw Rejection.notYet(where, "Leaving out an argument that has a default")
			}
			arguments += values.sortedBy { it.textOffset }
		}
		return arguments
	}

	/**
	 * A call's arguments as Lua passes them, in the order of the parameters they go to: a vararg's values one by one,
	 * and nil for a parameter left to its default before one that is given; those left out at the end stay out.
	 */
	fun arguments(call: KaFunctionCall<*>, where: KtCallExpression): List<Expression> {
		val byParameter = call.valueArgumentMapping.entries.groupBy({ it.value.symbol }, { it.key })
		val passed = mutableListOf<KtExpression?>()
		for (parameter in call.symbol.valueParameters) {
			val values = byParameter[parameter]
			when {
				values != null -> passed += values.sortedBy { it.textOffset }
				parameter.isVararg -> {}
				else -> passed += null
			}
		}
		while (passed.isNotEmpty() && passed.last() == null) {
			passed.removeLast()
		}
		val given = passed.filterNotNull()
		val written = given.sortedBy { it.textOffset }
		if (given != written && given.any { PsiTreeUtil.findChildOfType(it, KtCallExpression::class.java, false) != null
				|| it is KtCallExpression }) {
			throw Rejection(where, "Kotlin evaluates named arguments in the order they are written, but Lua in the"
				+ " order of the parameters; write them in that order, or keep the calls' values in locals first.")
		}
		return passed.map { if (it == null) Expression.Nil() else argument(it) }
	}

	/**
	 * A library call's arguments, in the order of the parameters they go to and by the parameters' names. A library
	 * function's defaults are known, so the arguments a call leaves to them are left out.
	 */
	fun libraryArguments(call: KaFunctionCall<*>, where: KtCallExpression): LibraryArguments {
		val byParameter = call.valueArgumentMapping.entries.groupBy({ it.value.symbol }, { it.key })
		val arguments = mutableListOf<KtExpression>()
		val byName = mutableMapOf<String, KtExpression>()
		for (parameter in call.symbol.valueParameters) {
			val values = byParameter[parameter]?.sortedBy { it.textOffset } ?: continue
			arguments += values
			byName[parameter.name.asString()] = values.first()
		}
		return LibraryArguments(where, arguments, byName)
	}

	/** An argument as Lua passes it: a spread array, `*values`, as the values it holds, `table.unpack(values)`. */
	fun argument(value: KtExpression): Expression {
		val spread = (value.parent as? KtValueArgument)?.getSpreadElement() != null
		if (!spread) {
			return expression(value)
		}
		unit.useGlobal("table")
		return Expression.call(Expression.field(Expression.name("table"), "unpack"), expression(value))
	}

	/** `list[i]` and `map[key]`. */
	private fun index(access: KtArrayAccessExpression): Expression {
		val receiver = access.arrayExpression ?: throw Rejection(access, EMPTY)
		val key = access.indexExpressions.singleOrNull()
			?: throw Rejection.notYet(access, "Indexing with several values")
		return library.index(access, Receiver.Written(receiver), key)
	}

	/** A position Kotlin counts from 0 as Lua's, one higher, unless it names a loop variable that counts from 1. */
	fun position(index: KtExpression): Expression =
		expression(index).let { if (oneBasedVariable(index)) it else IndexArithmetic.plusOne(it) }

	/** Whether an expression names the variable of a loop that counts from 1. */
	fun oneBasedVariable(expression: KtExpression): Boolean {
		if (expression !is KtNameReferenceExpression) {
			return false
		}
		val declaration = with(unit.session) { expression.resolveSymbol()?.psi }
		return declaration is KtNamedDeclaration && declaration in unit.oneBased
	}

	companion object {

		/** What Lua makes of a value whose code runs as statements, with its function, for a message. */
		const val STATEMENTS_FIRST = "In Lua the code of %s runs as statements"

		private const val EMPTY = "This expression is incomplete and does not compile."

		private const val NO_CLASSES = "Lua has no runtime type information, so a value has no class to read; test" +
			" it with is instead."

		/** What to write instead of a value whose code cannot run before the statement it is in. */
		private const val LOCAL_FIRST = "give its value to a local of its own first, val x = ..."

		/** Why the Lua of `?.` reads its receiver twice, for a message. */
		private const val NIL_TEST = "Lua reads this value twice to test it for nil"

		/** Why the Lua of an assignment that reads its target works the target's parts out twice, for a message. */
		private const val READ_TARGET = "Lua works this value out twice, to read the target and to assign it"

		/** The longest call a message shows as written. */
		private const val SHOWN_CALL = 40
		private val FUNCTION_TYPE =
			Regex("kotlin\\.(jvm\\.functions\\.)?Function\\d+|kotlin\\.reflect\\.KFunction\\d*")
		private val ASSIGNMENTS = setOf(KtTokens.EQ, KtTokens.PLUSEQ, KtTokens.MINUSEQ, KtTokens.MULTEQ,
			KtTokens.DIVEQ, KtTokens.PERCEQ)
		private val STEPS = setOf(KtTokens.PLUSPLUS, KtTokens.MINUSMINUS)

		/** The operators whose right side runs only sometimes. */
		private val SHORT_CIRCUIT = setOf(KtTokens.ANDAND, KtTokens.OROR, KtTokens.ELVIS)

		/** The name Kotlin gives a parameter of a compiled Java class that keeps no names. */
		private val UNNAMED_PARAMETER = Regex("p\\d+")

		/** The names of made-up locals that hold a list, a set or a map; see [collectionName]. */
		val COLLECTIONS = setOf("list", "set", "map", "groups")
	}
}
