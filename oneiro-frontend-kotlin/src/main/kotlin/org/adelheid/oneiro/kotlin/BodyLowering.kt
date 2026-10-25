package org.adelheid.oneiro.kotlin

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.adelheid.oneiro.lowering.ClientLimits
import org.adelheid.oneiro.lowering.IndexArithmetic
import org.adelheid.oneiro.lowering.LuaPatterns
import org.adelheid.oneiro.lowering.TableFolding
import org.adelheid.oneiro.lua.BinaryOperator
import org.adelheid.oneiro.lua.Block
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.Statement
import org.adelheid.oneiro.lua.Trivia
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.analysis.api.resolution.KaCallableMemberCall
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
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
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBreakExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtContinueExpression
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
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

	// The statements that the ifs and whens inside the statement being lowered run before it, and that statement; see
	// [hoisted]
	private var hoisting: MutableList<Statement>? = null
	private var hoistRoot: KtExpression? = null
	// The names the locals of hoisted values have taken
	private val hoistedNames = mutableSetOf<String>()

	/** A lowering for code nested in this one's, such as a lambda's, which runs on the same object. */
	fun nested(): BodyLowering = BodyLowering(unit, owner, instance)

	/**
	 * A block's statements, each with the comments above it and after it, and the comments that close the block. With
	 * [last], the final statement is lowered by it instead, as a branch that gives a value hands its last expression
	 * on. A statement Oneiro rejects is reported, and the statements after it are still checked.
	 */
	fun block(block: KtBlockExpression, last: ((KtExpression) -> List<Statement>)? = null): Block {
		val entries = mutableListOf<Block.Entry>()
		val statements = block.statements
		for ((index, statement) in statements.withIndex()) {
			val lower = if (index == statements.lastIndex && last != null) lowerBy(last) else null
			try {
				entries += entries(statement, lower)
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
	 */
	private fun entries(statement: KtExpression, lower: ((KtExpression) -> List<Block.Entry>)?): List<Block.Entry> {
		val blankLine = source.blankLineBefore(statement)
		val leading = source.leading(statement)
		val lowered = hoistingInto(statement) { lower?.invoke(statement) ?: statementEntries(statement) }
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

	/** Lowers a statement, after the statements of the ifs and whens inside it that run before it; see [hoisted]. */
	private fun hoistingInto(statement: KtExpression, lower: () -> List<Block.Entry>): List<Block.Entry> {
		val outer = hoisting
		val outerRoot = hoistRoot
		val hoisted = mutableListOf<Statement>()
		hoisting = hoisted
		hoistRoot = statement
		try {
			val lowered = lower()
			return hoisted.map(Block.Entry::of) + lowered
		} finally {
			hoisting = outer
			hoistRoot = outerRoot
		}
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
	private fun statementEntries(statement: KtExpression): List<Block.Entry> {
		if (statement is KtTryExpression) {
			return exceptions.tryStatement(statement)
		}
		// target = receiver.apply { ... } on a target that reading again gives the same, a name or a property
		val target = (statement as? KtBinaryExpression)?.takeIf { it.operationToken == KtTokens.EQ }?.left
		val scope = (statement as? KtBinaryExpression)?.right?.let(scopes::scope)
		if (target != null && scope != null && isStable(target)) {
			scopes.assigned(scope, assignmentTarget(target))?.let { return it }
		}
		return scopes.entries(statement) ?: statement(statement).map(Block.Entry::of)
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
		is KtDestructuringDeclaration -> listOf(returnValues.destructured(statement))
		is KtClass -> objects.localClass(statement)
		is KtLabeledExpression -> statement(statement.baseExpression ?: throw Rejection(statement, EMPTY))
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
		scopes.scope(value)?.let { return scopes.statements(it, sink) }
		return branches.deliver(value, sink)
	}

	/**
	 * The statements that hand a value to the sink: an `if` or `when` hands each branch's on, and code that jumps runs
	 * as it is.
	 */
	fun handedOn(value: KtExpression, sink: (Expression) -> Statement): List<Statement> =
		if (Branches.jumps(value)) statement(value) else delivered(value, sink) ?: listOf(sink(expression(value)))

	/** Whether an expression is `run { ... }` with no receiver, a block of code with locals of its own. */
	private fun isRun(expression: KtExpression): Boolean = with(unit.session) {
		val call = expression as? KtCallExpression ?: return false
		val function = call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol ?: return false
		function.callableId?.callableName?.asString() == "run" && isLibrary(function) &&
			call.lambdaArguments.size == 1 && call.valueArguments.size == 1
	}

	/** `run { ... }` as a statement: Lua's `do ... end`, whose locals end with it. */
	private fun scoped(call: KtCallExpression): Statement {
		val lambda = call.lambdaArguments.single().getLambdaExpression() ?: throw Rejection(call, EMPTY)
		val literal = lambda.functionLiteral
		unit.inlinedLambdas += literal
		return Statement.Do(block(literal.bodyBlockExpression ?: throw Rejection(lambda, EMPTY)))
	}

	/** Whether an expression is `resource.use { ... }`, which closes the resource after the lambda's code. */
	private fun isUse(expression: KtExpression): Boolean = with(unit.session) {
		val call = (expression as? KtQualifiedExpression)?.selectorExpression as? KtCallExpression ?: return false
		val function = call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol ?: return false
		function.callableId?.callableName?.asString() == "use" && isLibrary(function)
	}

	/**
	 * A name for a local the Lua needs that the source does not have, such as `pcall`'s `ok`: the preferred one,
	 * unless the function around declares it, then the preferred one with a number.
	 */
	fun invent(preferred: String, where: PsiElement): String {
		val scope = PsiTreeUtil.getParentOfType(where, KtDeclarationWithBody::class.java) ?: where.containingFile
		val declared = PsiTreeUtil.collectElementsOfType(scope, KtNamedDeclaration::class.java)
			.mapNotNull { it.name?.let(unit.names::luaName) }.toSet()
		var name = preferred
		var number = 2
		while (name in declared) {
			name = preferred + number++
		}
		return name
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

	/** `receiver.member(...)` as a statement, when the member is one of the library's that changes a collection. */
	private fun collectionStatement(expression: KtExpression): List<Statement>? = with(unit.session) {
		val qualified = expression as? KtDotQualifiedExpression ?: return null
		val call = qualified.selectorExpression as? KtCallExpression ?: return null
		val resolved = call.resolveToCall()?.successfulFunctionCallOrNull() ?: return null
		if (!isLibrary(resolved.symbol)) {
			return null
		}
		library.statement(expression, resolved.symbol, qualified.receiverExpression, libraryArguments(resolved, call))
	}

	/**
	 * `val x = value` as `local x = value`. A value from `if` or `when` is assigned in each branch, and
	 * `val x = value ?: return` leaves when the value is null.
	 */
	private fun local(property: KtProperty): List<Statement> {
		val name = unit.localName(property, property.name!!)
		val local = Expression.name(name)
		val initializer = property.initializer ?: return listOf(Statement.Local(listOf(name), listOf()))
		returnValues.components(with(unit.session) { initializer.expressionType })?.let { components ->
			return returnValues.local(property, initializer, components)
		}
		chains.chain(initializer)?.let { return chains.lower(it, CollectionLoops.Destination.Local(name)) }
		if (preconditions.isNotNull(initializer)) {
			return preconditions.notNullLocal(name, initializer as KtCallExpression)
		}
		delivered(initializer) { Statement.assignment(local, it) }?.let {
			return listOf(Statement.Local(listOf(name), listOf())) + it
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
		val target = assignmentTarget(targetTree)
		if (assignment.operationToken == KtTokens.EQ) {
			delivered(valueTree) { Statement.assignment(target, it) }?.let { return it }
		}
		// x /= y and x %= y round as Kotlin's / and % do
		val targetType = with(unit.session) { targetTree.expressionType }
		if (targetType != null && types.isString(targetType) && assignment.operationToken == KtTokens.PLUSEQ) {
			// text += value appends the value as text
			return listOf(Statement.assignment(target, Expression.binary(target, BinaryOperator.CONCAT,
				strings.concatOperand(valueTree))))
		}
		when (assignment.operationToken) {
			KtTokens.DIVEQ -> return listOf(Statement.assignment(target, operators.division(targetTree, valueTree)))
			KtTokens.PERCEQ -> return listOf(Statement.assignment(target, operators.remainder(targetTree, valueTree)))
		}
		val value = expression(valueTree)
		val operator = when (assignment.operationToken) {
			KtTokens.EQ -> return listOf(Statement.assignment(target, value))
			KtTokens.PLUSEQ -> BinaryOperator.ADD
			KtTokens.MINUSEQ -> BinaryOperator.SUBTRACT
			KtTokens.MULTEQ -> BinaryOperator.MULTIPLY
			else -> throw Rejection.notYet(assignment, "The operator ${assignment.operationReference.text}")
		}
		return listOf(Statement.assignment(target, Expression.binary(target, operator, value)))
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
		val target = expression(variable)
		if (chars.isChar(variable)) {
			return Statement.assignment(target, chars.stepped(target, up))
		}
		val operator = if (up) BinaryOperator.ADD else BinaryOperator.SUBTRACT
		return Statement.assignment(target, Expression.binary(target, operator, Expression.integer(1)))
	}

	// ---- Expressions

	fun expression(expression: KtExpression): Expression = when (expression) {
		is KtConstantExpression -> constant(expression)
		is KtStringTemplateExpression -> string(expression)
		is KtNameReferenceExpression -> reference(expression, null)
		is KtDotQualifiedExpression -> qualified(expression)
		is KtCallExpression -> call(expression, null)
		is KtThisExpression -> self(expression)
		is KtSuperExpression -> self(expression)
		is KtParenthesizedExpression -> expression(expression.expression ?: throw Rejection(expression, EMPTY))
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
	 * its value, which each branch assigns, `local value` and `if ... then value = ... end`, where nothing the
	 * statement does would see the difference; see [canHoist].
	 */
	private fun hoisted(value: KtExpression): Expression {
		val statements = hoisting
		val root = hoistRoot
		if (statements == null || root == null || !canHoist(value, root)) {
			val kind = if (value is KtWhenExpression) "when" else "if"
			throw Rejection(value, "In Lua this $kind is a statement, which can run before the code around it only"
				+ " where nothing would see the difference; give its value to a local of its own first, val x = $kind"
				+ " ...")
		}
		val preferred = invent(hoistedName(value), value)
		var name = preferred
		var number = 2
		while (name in hoistedNames) {
			name = preferred + number++
		}
		hoistedNames += name
		statements += Statement.Local(listOf(name), listOf())
		statements += branches.deliver(value) { Statement.assignment(Expression.name(name), it) }
			?: throw IllegalStateException("an if or when that gives no value: ${value.text}")
		return Expression.name(name)
	}

	/**
	 * Whether an `if` or `when` inside a statement can run before the statement: the statement runs it each time it
	 * runs, not only when an `&&`, an `||`, a `?:`, a `?.` or a branch gets to it, nor once each turn of a loop or each
	 * element of a chain; and nothing the statement evaluates before it calls a function, whose effects would then
	 * come after the branches' code.
	 */
	private fun canHoist(value: KtExpression, root: KtExpression): Boolean {
		var node: PsiElement = value
		while (node != root) {
			val parent = node.parent ?: return false
			val inside = { part: PsiElement? -> part != null && PsiTreeUtil.isAncestor(part, value, false) }
			val sometimes = when (parent) {
				is KtFunctionLiteral -> true
				is KtBinaryExpression -> parent.operationToken in SHORT_CIRCUIT && inside(parent.right)
				is KtIfExpression -> !inside(parent.condition)
				is KtWhenExpression -> !inside(parent.subjectExpression)
				is KtWhileExpressionBase -> inside(parent.condition)
				is KtSafeQualifiedExpression -> inside(parent.selectorExpression)
				else -> false
			}
			if (sometimes) {
				return false
			}
			node = parent
		}
		val start = value.textRange.startOffset
		return PsiTreeUtil.collectElementsOfType(root, KtCallExpression::class.java)
			.none { it.textRange.endOffset <= start }
	}

	/** The name of a hoisted value's local: the parameter's that it is passed to, or `value`. */
	private fun hoistedName(value: KtExpression): String = with(unit.session) {
		val argument = value.parent as? KtValueArgument ?: return "value"
		val call = PsiTreeUtil.getParentOfType(argument, KtCallExpression::class.java) ?: return "value"
		val parameter = call.resolveToCall()?.successfulFunctionCallOrNull()?.valueArgumentMapping
			?.get(argument.getArgumentExpression())?.symbol ?: return "value"
		if (parameter.isVararg) "value" else parameter.name.asString()
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
		val test = Expression.binary(expression(condition), BinaryOperator.AND, expression(then))
		return Expression.binary(test, BinaryOperator.OR, expression(otherwise))
	}

	/** Whether an `if` has single values that are never false or nil, so that Lua can say it as `c and a or b`. */
	private fun isSafeConditional(conditional: KtIfExpression): Boolean {
		val then = conditional.then
		val otherwise = conditional.`else`
		val type = then?.let { with(unit.session) { it.expressionType } }
		return then != null && then !is KtBlockExpression && otherwise != null && otherwise !is KtBlockExpression &&
			otherwise !is KtIfExpression && type != null && !types.isBoolean(type) && !types.isNullable(type)
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
	 * twice, so it must be a name or a property read, and must never be false.
	 */
	private fun safeCall(safe: KtSafeQualifiedExpression): Expression {
		val receiver = safe.receiverExpression
		val type = with(unit.session) { receiver.expressionType }
		if (!isStable(receiver) || type == null || types.isBoolean(type)) {
			throw Rejection(safe, "Lua reads this value twice to test it for nil; store it in a local first.")
		}
		return Expression.binary(expression(receiver), BinaryOperator.AND, qualified(safe))
	}

	/**
	 * `receiver?.let { x -> ... }` as a statement: `local x = receiver`, then the lambda's code when it is not nil;
	 * `receiver?.member(...)` runs the call when the receiver, a name or a property read, is not nil.
	 */
	private fun safeStatement(safe: KtSafeQualifiedExpression): List<Statement> {
		val receiver = safe.receiverExpression
		val call = safe.selectorExpression as? KtCallExpression
		val lambda = call?.lambdaArguments?.singleOrNull()?.getLambdaExpression()
		val function = with(unit.session) { call?.resolveToCall()?.successfulFunctionCallOrNull()?.symbol }
		val isLet = function != null && isLibrary(function) && function.callableId?.callableName?.asString() == "let"
		if (isLet && lambda != null) {
			val parameter = lambda.functionLiteral.valueParameters.singleOrNull()
			if (parameter?.destructuringDeclaration != null) {
				throw Rejection.notYet(parameter, "A lambda parameter that destructures")
			}
			val name = unit.localName(parameter ?: lambda.functionLiteral, parameter?.name ?: "it")
			val value = Expression.name(name)
			val code = lambda.bodyExpression ?: throw Rejection(lambda, EMPTY)
			val present = Expression.binary(value, BinaryOperator.NOT_EQUAL, Expression.Nil())
			return listOf(Statement.Local(listOf(name), listOf(expression(receiver))), Statement.ifThen(present,
				block(code)))
		}
		if (!isStable(receiver)) {
			throw Rejection(safe, "Lua reads this value twice to test it for nil; store it in a local first.")
		}
		val present = Expression.binary(expression(receiver), BinaryOperator.NOT_EQUAL, Expression.Nil())
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
						throw Rejection(reference, CollectionLoops.ENTRY_PARTS)
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
			if (receiver == null && implicitReceiver(reference) != null) {
				throw Rejection(where, unnamedLibraryReceiver(reference.getReferencedName()))
			}
			return library.property(where, symbol, receiver)
		}
		val classId = symbol.callableId?.classId
		val declaration = symbol.psi
		val name = reference.getReferencedName()
		if (receiver == null && declaration is KtParameter && inInitializer(reference)) {
			// In an init block or a property's value, the name of a constructor's property is its parameter
			return Expression.name(unit.localName(declaration, name))
		}
		if (declaration is KtProperty && declaration.parent is KtFile) {
			if (declaration.containingFile != unit.file) {
				throw Rejection.notYet(where, "Reading a property of another file")
			}
			return Expression.name(unit.names.luaName(name))
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
			else -> throw Rejection(receiver, CollectionLoops.ENTRY_PARTS)
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
	 * `this`, `apply { SetPos(1, 2) }`: its Lua name; null when it is the class's own object, or there is none.
	 */
	private fun implicitReceiver(member: KtElement, extension: Boolean = false): Expression? = with(unit.session) {
		val call = member.resolveToCall()?.successfulCallOrNull<KaCallableMemberCall<*, *>>() ?: return null
		val applied = call.partiallyAppliedSymbol
		// An extension function's own receiver, or the object a member is read on
		val receivers = if (extension) listOf(applied.extensionReceiver)
		else listOf(applied.dispatchReceiver, applied.extensionReceiver)
		receivers.firstNotNullOfOrNull { receiver ->
			val owner = ((receiver as? KaImplicitReceiverValue)?.symbol as? KaReceiverParameterSymbol)
				?.owningCallableSymbol?.psi
			unit.receiverAlias(owner)
		}
	}

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
			// A value of a function type, called: f(x) or f.invoke(x)
			val function = receiver ?: call.calleeExpression ?: throw Rejection(call, EMPTY)
			return Expression.Call(expression(function), arguments(resolved, call))
		}
		if (preconditions.isNotNull(call)) {
			return preconditions.notNullValue(call)
		}
		scopes.scope(call, receiver, where)?.let { return scopes.value(it) }
		if (receiver == null && isLibrary(symbol) && implicitReceiver(call) != null) {
			throw Rejection(where, unnamedLibraryReceiver("${symbol.callableId?.callableName}(...)"))
		}
		if (api.owner(symbol)?.classId?.asFqNameString() == LuaPatterns.CLASS) {
			if (symbol.callableId?.callableName?.asString() == "gmatch") {
				throw Rejection(where, LuaPatterns.GMATCH_OUTSIDE_LOOP)
			}
			return LuaPatterns.call(symbol.callableId!!.callableName.asString(), arguments(resolved, call))
		}
		if (isLibrary(symbol)) {
			return library.call(where, symbol, receiver, libraryArguments(resolved, call))
		}
		if (symbol !is KaNamedFunctionSymbol) {
			throw Rejection.notYet(where, "Calling this")
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
		return pluginCall(where, symbol, ownerClass, receiver, arguments)
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
	 */
	private fun pluginCall(call: KtExpression, function: KaNamedFunctionSymbol, ownerClass: KaClassSymbol,
		receiver: KtExpression?, arguments: List<Expression>): Expression = with(unit.session) {
		val name = unit.memberName(function)
		val ownerDeclaration = ownerClass.psi as? KtClassOrObject
		val declaration = function.psi
		val isPrivate = declaration is KtNamedFunction && declaration.isPrivateInLua() &&
			declaration.containingFile == unit.file
		return when {
			// An object expression that is a Lua function has no methods to call
			ownerDeclaration is KtObjectDeclaration && ownerDeclaration.isObjectLiteral() &&
				objects.isFunction(ownerDeclaration) -> Expression.Call(receiverOrSelf(receiver, call), arguments)
			// super.f(): the superclass's function, called on this object
			receiver is KtSuperExpression ->
				Expression.Call(Expression.field(unit.moduleReference(ownerClass), name), listOf(self(receiver)) +
					arguments)
			// A value of a plugin's fun interface is a Lua function, which has no methods to call
			(ownerDeclaration as? KtClass)?.hasModifier(KtTokens.FUN_KEYWORD) == true &&
				isValueOf(receiver, ownerClass) -> {
				if (function.modality != KaSymbolModality.ABSTRACT) {
					val type = ownerClass.classId?.shortClassName
					throw Rejection(call, "A value of $type is a Lua function, which carries no methods; make"
						+ " ${function.name}() a function outside the interface that takes the $type.")
				}
				Expression.Call(receiverOrSelf(receiver, call, ownerClass), arguments)
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
			isPrivate -> Expression.Call(Expression.name(unit.names.luaName(name)),
				listOf(receiverOrSelf(receiver, call, ownerClass)) + arguments)
			else -> Expression.MethodCall(receiverOrSelf(receiver, call, ownerClass), name, arguments)
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

	/** Whether reading an expression again gives the same value and does nothing else: a name, or a property read. */
	fun isStable(expression: KtExpression): Boolean = when (expression) {
		is KtNameReferenceExpression, is KtThisExpression -> true
		is KtDotQualifiedExpression -> expression.selectorExpression is KtNameReferenceExpression &&
			isStable(expression.receiverExpression)
		else -> false
	}

	/** `list[i]` and `map[key]`. */
	private fun index(access: KtArrayAccessExpression): Expression {
		val receiver = access.arrayExpression ?: throw Rejection(access, EMPTY)
		val key = access.indexExpressions.singleOrNull()
			?: throw Rejection.notYet(access, "Indexing with several values")
		return library.index(access, receiver, key)
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
		return declaration is KtParameter && declaration in unit.oneBased
	}

	private companion object {
		const val EMPTY = "This expression is incomplete and does not compile."

		/** Why a member of the Kotlin library needs its receiver written out, as `this.member`. */
		fun unnamedLibraryReceiver(member: String): String = "Oneiro reads the Kotlin library's members only on a" +
			" value the code names; write this.$member here."

		const val NO_CLASSES = "Lua has no runtime type information, so a value has no class to read; test it with" +
			" is instead."
		val FUNCTION_TYPE = Regex("kotlin\\.(jvm\\.functions\\.)?Function\\d+|kotlin\\.reflect\\.KFunction\\d*")
		val ASSIGNMENTS = setOf(KtTokens.EQ, KtTokens.PLUSEQ, KtTokens.MINUSEQ, KtTokens.MULTEQ, KtTokens.DIVEQ,
			KtTokens.PERCEQ)
		val STEPS = setOf(KtTokens.PLUSPLUS, KtTokens.MINUSMINUS)

		/** The operators whose right side runs only sometimes. */
		val SHORT_CIRCUIT = setOf(KtTokens.ANDAND, KtTokens.OROR, KtTokens.ELVIS)
	}
}
