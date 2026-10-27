package org.adelheid.oneiro.kotlin.language

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.adelheid.oneiro.kotlin.core.BodyLowering
import org.adelheid.oneiro.kotlin.core.Rejection
import org.adelheid.oneiro.lua.Block
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.FunctionBody
import org.adelheid.oneiro.lua.Statement
import org.adelheid.oneiro.lua.Trivia
import org.adelheid.oneiro.lua.UnaryOperator
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBreakExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtContinueExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtLoopExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtTryExpression
import org.jetbrains.kotlin.psi.KtWhenExpression

/**
 * Kotlin's exceptions as Lua's errors. `throw` raises the exception's message with `error`, and `try` runs its block
 * as a function under `pcall`, the catch block running when that raised an error; `finally` and `use` run their code
 * after it whatever happened, then raise the error again. Lua errors carry a value, not a class, so a catch takes
 * every error.
 */
internal class Exceptions(private val body: BodyLowering) {

	private val unit = body.unit

	/**
	 * `throw SomeException(message)` as `error(message)`: only the message survives, or the exception class's name
	 * when there is none. A caught error thrown again is raised as it is, `error(e, 0)`, so that its message keeps the
	 * place it was raised.
	 */
	fun throwStatement(thrown: KtThrowExpression): Statement =
		Statement.CallStatement(errorCall(thrown.thrownExpression ?: throw Rejection(thrown, EMPTY)))

	/** `error(message)` for a thrown value: a new exception's message, or its class's name when it has none. */
	fun errorCall(thrown: KtExpression): Expression = with(unit.session) {
		unit.useGlobal("error")
		if (isCaughtError(thrown)) {
			return Expression.call(Expression.name("error"), body.expression(thrown), Expression.integer(0))
		}
		val created = thrown as? KtCallExpression
		val constructor = created?.resolveToCall()?.successfulFunctionCallOrNull()?.symbol as? KaConstructorSymbol
		val message = when {
			created == null || constructor == null -> body.expression(thrown)
			created.valueArguments.isEmpty() ->
				Expression.string(constructor.returnType.expandedSymbol?.classId?.shortClassName?.asString() ?: "error")
			else -> body.strings.asText(created.valueArguments[0].getArgumentExpression()
				?: throw Rejection(created, EMPTY))
		}
		Expression.call(Expression.name("error"), message)
	}

	/** Whether an expression names a catch's parameter, the error value `pcall` handed back. */
	fun isCaughtError(expression: KtExpression): Boolean {
		val name = expression as? KtNameReferenceExpression ?: return false
		val declaration = with(unit.session) { name.resolveSymbol()?.psi }
		return declaration is KtParameter && declaration.parent?.parent is KtCatchClause
	}

	/**
	 * `try { ... } catch (e: Exception) { ... }` as `pcall`: the try block becomes a function that `pcall` runs, and
	 * the catch block runs when it raised an error, with `e` the error value.
	 *
	 * ```
	 * local ok, e = pcall(function()
	 *     ...
	 * end)
	 * if not ok then
	 *     ...
	 * end
	 * ```
	 *
	 * A try block that returns must return on every path, and its value is returned after `pcall`:
	 * `if ok then return result else ... end`.
	 */
	fun tryStatement(attempt: KtTryExpression, value: TryValue? = null): List<Block.Entry> = with(unit.session) {
		val clauses = attempt.catchClauses
		if (clauses.size > 1) {
			throw Rejection(clauses[1], "Lua errors carry a value, not a class, so one catch takes every error; catch"
				+ " Exception once, and tell the errors apart inside it.")
		}
		val handler = clauses.firstOrNull()
		val parameter = handler?.catchParameter
		val caught = parameter?.symbol?.returnType?.expandedSymbol?.classId
		if (parameter != null && caught !in CATCH_ALL) {
			throw Rejection(parameter, "Lua errors carry a value, not a class, so a catch cannot tell exception"
				+ " classes apart; catch Exception or Throwable, and tell the errors apart inside it.")
		}
		val block = attempt.tryBlock
		checkStaysInside(block)
		val returns = value?.returning == true || completesByReturning(block, attempt, "try")
		val finallyBlock = attempt.finallyBlock?.finalExpression
		if (finallyBlock != null) {
			return tryFinally(attempt, lowered(block, value), returns, handler, body.block(finallyBlock),
				catchValue = value)
		}
		if (handler == null || parameter == null) {
			throw IllegalStateException("a try without catch or finally")
		}
		val attempted = Expression.FunctionExpression(FunctionBody.of(listOf(), lowered(block, value)))
		unit.useGlobal("pcall")
		val run = Expression.call(Expression.name("pcall"), attempted)
		val ok = body.invent("ok", attempt)
		val errorName = unit.localName(parameter, parameter.name!!)
		val catchBody = handler.catchBody ?: throw Rejection(handler, EMPTY)
		val caughtBlock = if (value == null) body.block(catchBody) else body.block(catchBody, value.last)
		if (!returns) {
			// The error value is named only when the catch block reads it
			val names = if (body.uses(handler.catchBody, parameter)) listOf(ok, errorName) else listOf(ok)
			val failed = Expression.Unary(UnaryOperator.NOT, Expression.name(ok))
			return listOf(Statement.Local(names, listOf(run)), Statement.ifThen(failed, caughtBlock))
				.map(Block.Entry::of)
		}
		val gives = value?.returning == true || returnsValue(block)
		val result = if (gives) body.invent("result", attempt) else errorName
		val otherwise = mutableListOf<Block.Entry>()
		if (gives && body.uses(handler.catchBody, parameter)) {
			// The second value is the result when pcall succeeded and the error when it did not
			otherwise += Block.Entry.of(Statement.Local(listOf(errorName), listOf(Expression.name(result))))
		}
		otherwise += caughtBlock.entries()
		val succeeded = Block.of(Statement.Return(if (gives) listOf(Expression.name(result)) else listOf()))
		listOf(Statement.Local(listOf(ok, result), listOf(run)),
			Statement.If(listOf(Statement.If.Branch(Expression.name(ok), succeeded)),
				Block(otherwise, caughtBlock.closingComments()))).map(Block.Entry::of)
	}

	/**
	 * What a `try` used as a value does with each block's last expression.
	 *
	 * @property last lowers a block's last statement: hands its value on, or leaves as it is when it jumps
	 * @property returning whether the value is returned, so that every block returns
	 */
	class TryValue(val last: (KtExpression) -> List<Statement>, val returning: Boolean)

	/** A try block's code, which hands its last expression on when the `try` is a value. */
	private fun lowered(block: KtExpression, value: TryValue?): Block {
		val inner = body.nested()
		return if (value == null) inner.block(block) else inner.block(block, value.last)
	}

	/**
	 * An exception kept as a value, `IllegalStateException("broken")`, is its message, the value `error` raises; or
	 * the class's name when it has none.
	 */
	fun exceptionValue(created: KtCallExpression, type: KaClassSymbol): Expression {
		val message = created.valueArguments.firstOrNull()?.getArgumentExpression()
			?: return Expression.string(type.classId?.shortClassName?.asString() ?: "error")
		return body.expression(message)
	}

	/** Whether a type is an exception's, which in Lua is only its message. */
	fun isThrowable(type: KaClassSymbol): Boolean = with(unit.session) {
		val throwables = setOf(StandardClassIds.Throwable, ClassId.fromString("java/lang/Throwable"))
		type.classId in throwables || type.defaultType.allSupertypes.any { it.expandedSymbol?.classId in throwables }
	}

	/**
	 * `resource.use { r -> ... }`: the resource kept in a local named after the lambda's parameter, then the lambda's
	 * code under `pcall`, the resource closed after it whatever happened, as a finally block would.
	 *
	 * @param sink what the lambda's value goes to, a `return` or an assignment, or null when nothing takes it
	 */
	fun use(call: KtQualifiedExpression, sink: ((Expression) -> Statement)?): List<Statement> = with(unit.session) {
		val resource = call.receiverExpression
		val lambda = (call.selectorExpression as? KtCallExpression)?.lambdaArguments?.singleOrNull()
			?.getLambdaExpression() ?: throw Rejection.notYet(call, "use with anything but a lambda")
		val type = resource.expressionType?.expandedSymbol
		if (type == null || !unit.isPluginType(type)) {
			throw Rejection(resource, "Lua has none of this class's code to close it with; use a resource class of the"
				+ " plugin's, which declares close().")
		}
		val parameter = lambda.functionLiteral.valueParameters.singleOrNull()
		val name = unit.localName(parameter ?: lambda.functionLiteral, parameter?.name ?: "it")
		val code = lambda.bodyExpression ?: throw Rejection(lambda, EMPTY)
		checkStaysInside(code)
		val inner = body.nested()
		val returns = sink != null || completesByReturning(code, call, "use")
		val lowered = when {
			code.statements.isEmpty() -> Block.of()
			sink != null -> inner.block(code) { last -> inner.returned(last) }
			else -> inner.block(code)
		}
		val close = Statement.CallStatement(Expression.MethodCall(Expression.name(name), "close", listOf()))
		val statements = mutableListOf<Statement>(Statement.Local(listOf(name), listOf(body.expression(resource))))
		val closing = tryFinally(call, lowered, returns, null, Block.of(close), value = true, sink = sink)
		statements + closing.map { it.statement() }
	}

	/**
	 * `try ... finally`: the try block, and a catch block, each in a function that `pcall` runs, so that the finally
	 * block runs whatever they do; then an error neither handled is raised again, unchanged, and a value they returned
	 * is returned.
	 *
	 * ```
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
	 * ```
	 *
	 * @param value whether the blocks give a value when they return; worked out from their returns when null
	 * @param sink what a returned value goes to, `return` unless another is given
	 */
	private fun tryFinally(attempt: KtExpression, tryBody: Block, tryReturns: Boolean, handler: KtCatchClause?,
		finallyBody: Block, value: Boolean? = null, sink: ((Expression) -> Statement)? = null,
		catchValue: TryValue? = null): List<Block.Entry> {
		var catchReturns = false
		var caught: Block? = null
		var errorName: String? = null
		if (handler != null) {
			val catchBody = handler.catchBody ?: throw Rejection(handler, EMPTY)
			checkStaysInside(catchBody)
			catchReturns = catchValue?.returning == true || completesByReturning(catchBody, handler, "catch")
			val caughtError = handler.catchParameter!!
			errorName = unit.localName(caughtError, caughtError.name!!)
			caught = lowered(catchBody, catchValue)
		}
		val returns = tryReturns || catchReturns
		val gives = returns && (value ?: (catchValue?.returning == true || returnsValue(attempt)))
		val ok = body.invent("ok", attempt)
		val result = body.invent(if (returns) "result" else "failure", attempt)
		val failed = Expression.Unary(UnaryOperator.NOT, Expression.name(ok))
		unit.useGlobal("pcall")
		val tryFunction = Expression.FunctionExpression(FunctionBody.of(listOf(), tryBody))
		val lowered = mutableListOf(Block.Entry.of(Statement.Local(listOf(ok, result),
			listOf(Expression.call(Expression.name("pcall"), tryFunction)))))
		// Which block's value to return when only one of them returns
		var returned: String? = null
		if (caught != null && tryReturns != catchReturns) {
			returned = body.invent("returned", attempt)
			lowered += Block.Entry.of(Statement.Local(listOf(returned),
				listOf(if (tryReturns) Expression.name(ok) else Expression.BooleanLiteral(false))))
		}
		if (caught != null && handler != null) {
			// The catch block's function takes the error only when it reads it
			val readsError = body.uses(handler.catchBody, handler.catchParameter!!)
			val catchFunction = Expression.FunctionExpression(FunctionBody.of(
				if (readsError) listOf(errorName!!) else listOf(), caught))
			val handled = if (readsError) {
				Expression.call(Expression.name("pcall"), catchFunction, Expression.name(result))
			} else {
				Expression.call(Expression.name("pcall"), catchFunction)
			}
			val handling = mutableListOf<Statement>(Statement.Assignment(listOf(Expression.name(ok),
				Expression.name(result)), listOf(handled)))
			if (returned != null && catchReturns) {
				handling += Statement.assignment(Expression.name(returned), Expression.name(ok))
			}
			lowered += Block.Entry.of(Statement.ifThen(failed, Block(handling.map(Block.Entry::of), listOf())))
		}
		// The finally block's statements keep their comments; the comments after its last one lead what follows
		lowered += finallyBody.entries()
		unit.useGlobal("error")
		val raise = Statement.CallStatement(Expression.call(Expression.name("error"), Expression.name(result),
			Expression.integer(0)))
		lowered += Block.Entry(Statement.ifThen(failed, Block.of(raise)), Trivia(false, finallyBody.closingComments(),
			null), null)
		val giveBack = when {
			sink != null && gives -> sink(Expression.name(result))
			else -> Statement.Return(if (gives) listOf(Expression.name(result)) else listOf())
		}
		if (returned != null) {
			lowered += Block.Entry.of(Statement.ifThen(Expression.name(returned), Block.of(giveBack)))
		} else if (returns) {
			lowered += Block.Entry.of(giveBack)
		}
		return lowered
	}

	/**
	 * Whether every path through a block returns, which a function `pcall` runs must tell apart from running to its
	 * end; false when no path returns. A block where some paths return and some do not is rejected.
	 */
	private fun completesByReturning(block: KtExpression, where: PsiElement, name: String): Boolean {
		val returns = returnsOf(block).isNotEmpty()
		if (returns && completesNormally(block)) {
			throw Rejection(where, "In Lua the $name block is a function that pcall runs, and Lua cannot tell a"
				+ " function that returned nothing from one that ran to its end; make every path through the $name"
				+ " block return, or none.")
		}
		return returns
	}

	/** Whether running a statement can reach its end, rather than always leaving with `return` or `throw`. */
	private fun completesNormally(statement: KtExpression): Boolean = when (statement) {
		is KtReturnExpression, is KtThrowExpression -> false
		is KtBlockExpression -> statement.statements.lastOrNull()?.let(::completesNormally) ?: true
		is KtIfExpression -> {
			val otherwise = statement.`else`
			otherwise == null || statement.then?.let(::completesNormally) != false || completesNormally(otherwise)
		}
		is KtWhenExpression -> statement.elseExpression == null ||
			statement.entries.any { entry -> entry.expression?.let(::completesNormally) != false }
		is KtTryExpression -> completesNormally(statement.tryBlock) ||
			statement.catchClauses.any { clause -> clause.catchBody?.let(::completesNormally) != false }
		else -> true
	}

	/** Whether a return of the code, not one inside a lambda or class, returns a value. */
	private fun returnsValue(code: KtExpression): Boolean = returnsOf(code).any { it.returnedExpression != null }

	/** The returns of the code that leave its function, not those inside a lambda or class. */
	private fun returnsOf(code: KtExpression): List<KtReturnExpression> =
		PsiTreeUtil.collectElementsOfType(code, KtReturnExpression::class.java).filter { returned ->
			val boundary = PsiTreeUtil.getParentOfType(returned, KtLambdaExpression::class.java, KtFunction::class.java,
				KtClassOrObject::class.java)
			boundary == null || !PsiTreeUtil.isAncestor(code, boundary, false)
		}

	/**
	 * The try block runs as a function of its own, so `break` and `continue` cannot leave it for the loop around it.
	 */
	private fun checkStaysInside(block: KtExpression) {
		for (jump in PsiTreeUtil.collectElementsOfType(block, KtExpression::class.java)) {
			if (jump !is KtBreakExpression && jump !is KtContinueExpression) {
				continue
			}
			val loop = PsiTreeUtil.getParentOfType(jump, KtLoopExpression::class.java, KtFunction::class.java)
			if (loop == null || !PsiTreeUtil.isAncestor(block, loop, false)) {
				throw Rejection(jump, "Lua runs the try block as a function, which a break or continue cannot leave;"
					+ " set a flag and act on it after the try.")
			}
		}
	}

	private companion object {
		const val EMPTY = "This expression is incomplete and does not compile."

		/** The classes a catch may name, each of which catches every error, as Lua's `pcall` does. */
		val CATCH_ALL = setOf(StandardClassIds.Throwable, ClassId.fromString("kotlin/Exception"),
			ClassId.fromString("kotlin/RuntimeException"), ClassId.fromString("java/lang/Exception"),
			ClassId.fromString("java/lang/RuntimeException"), ClassId.fromString("java/lang/Throwable"))
	}
}
