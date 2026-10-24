package org.adelheid.oneiro.kotlin

import com.intellij.psi.PsiComment
import org.adelheid.oneiro.lowering.IndexArithmetic
import org.adelheid.oneiro.lua.BinaryOperator
import org.adelheid.oneiro.lua.Block
import org.adelheid.oneiro.lua.Comment
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.Statement
import org.adelheid.oneiro.lua.Trivia
import org.adelheid.oneiro.lua.UnaryOperator
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaEnumEntrySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaJavaFieldSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaLocalVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBlockStringTemplateEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtSimpleNameStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtThisExpression

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
	private val api = ApiCalls(unit)

	/** A block's statements, each with the comments above it and after it, and the comments that close the block. */
	fun block(block: KtBlockExpression): Block {
		val entries = mutableListOf<Block.Entry>()
		for (statement in block.statements) {
			entries += entries(statement)
		}
		// Comments after the last statement, before the closing brace, close the block
		val closing = generateSequence(block.rBrace?.prevSibling) { it.prevSibling }
			.takeWhile { it !is KtExpression }
			.filterIsInstance<PsiComment>()
			.toList()
			.reversed()
			.flatMap { source.leading(it) + listOfNotNull(source.trailing(it)).map(Comment::line) }
		return Block(entries, closing)
	}

	/** A statement as block entries: the first carries the comments above it, the last the one after it. */
	private fun entries(statement: KtExpression): List<Block.Entry> {
		val blankLine = source.blankLineBefore(statement)
		val leading = source.leading(statement)
		val lowered = statement(statement)
		val trailing = source.trailing(statement)
		val origin = source.position(unit.codeStart(statement))
		return lowered.mapIndexed { index, lua ->
			val trivia = Trivia(index == 0 && blankLine, if (index == 0) leading else listOf(),
				if (index == lowered.lastIndex) trailing else null)
			Block.Entry(lua, trivia, origin)
		}
	}

	/** A branch's body: a block, or a single statement. */
	private fun body(expression: KtExpression): Block =
		if (expression is KtBlockExpression) block(expression) else Block(entries(expression), listOf())

	private fun statement(statement: KtExpression): List<Statement> = when (statement) {
		is KtProperty -> listOf(local(statement))
		is KtIfExpression -> listOf(ifChain(statement))
		is KtReturnExpression -> listOf(returnStatement(statement))
		is KtForExpression -> listOf(forLoop(statement))
		else -> expressionStatement(statement)
	}

	private fun returnStatement(statement: KtReturnExpression): Statement =
		Statement.Return(listOfNotNull(statement.returnedExpression?.let(::expression)))

	/** An expression used as a statement: a call, an assignment, or `++` and `--`. */
	fun expressionStatement(expression: KtExpression): List<Statement> = when {
		expression is KtBinaryExpression && expression.operationToken in ASSIGNMENTS -> listOf(assignment(expression))
		expression is KtPostfixExpression && expression.operationToken in STEPS ->
			listOf(step(expression.baseExpression, expression.operationToken == KtTokens.PLUSPLUS, expression))
		expression is KtPrefixExpression && expression.operationToken in STEPS ->
			listOf(step(expression.baseExpression, expression.operationToken == KtTokens.PLUSPLUS, expression))
		else -> when (val lowered = expression(expression)) {
			is Expression.Call, is Expression.MethodCall -> listOf(Statement.CallStatement(lowered))
			else -> throw Rejection(expression, "In Lua a statement is a call or an assignment; this expression's value"
				+ " would go nowhere, so remove it or use it.")
		}
	}

	private fun local(property: KtProperty): Statement {
		val name = unit.names.luaName(property.name!!)
		val initializer = property.initializer ?: throw Rejection.notYet(property, "A local without a value")
		return Statement.Local(listOf(name), listOf(expression(initializer)))
	}

	/** `target = value`, and `target += value` and its kin as `target = target + value`. */
	private fun assignment(assignment: KtBinaryExpression): Statement {
		val targetTree = assignment.left ?: throw Rejection(assignment, EMPTY)
		val valueTree = assignment.right ?: throw Rejection(assignment, EMPTY)
		checkAssigned(targetTree)
		val target = expression(targetTree)
		val value = expression(valueTree)
		val operator = when (assignment.operationToken) {
			KtTokens.EQ -> return Statement.assignment(target, value)
			KtTokens.PLUSEQ -> BinaryOperator.ADD
			KtTokens.MINUSEQ -> BinaryOperator.SUBTRACT
			KtTokens.MULTEQ -> BinaryOperator.MULTIPLY
			else -> throw Rejection.notYet(assignment, "The operator ${assignment.operationReference.text}")
		}
		val type = with(unit.session) { targetTree.expressionType }
		if (type != null && types.isString(type)) {
			throw Rejection.notYet(assignment, "Adding to a string with +=")
		}
		return Statement.assignment(target, Expression.binary(target, operator, value))
	}

	/** Assigning a field of the RS3 bindings that moderation reviews, such as a menu entry's `overrideText`. */
	private fun checkAssigned(target: KtExpression): Unit = with(unit.session) {
		val name = (if (target is KtDotQualifiedExpression) target.selectorExpression else target)
			as? KtNameReferenceExpression ?: return
		val field = name.resolveSymbol() as? KaJavaFieldSymbol ?: return
		val owner = api.owner(field) ?: return
		if (api.isApi(owner)) {
			api.assigned(field, owner, target)
		}
	}

	/** `x++` and `x--` as statements: `x = x + 1`. */
	private fun step(base: KtExpression?, up: Boolean, where: KtElement): Statement {
		val target = expression(base ?: throw Rejection(where, EMPTY))
		val operator = if (up) BinaryOperator.ADD else BinaryOperator.SUBTRACT
		return Statement.assignment(target, Expression.binary(target, operator, Expression.integer(1)))
	}

	/** `if`, `else if` and `else` as one Lua `if` with `elseif` branches. */
	private fun ifChain(statement: KtIfExpression): Statement {
		val branches = mutableListOf<Statement.If.Branch>()
		var current: KtIfExpression = statement
		while (true) {
			val condition = current.condition ?: throw Rejection(current, "An if without a condition does not compile.")
			val then = current.then ?: throw Rejection(current, "An if without a body does not compile.")
			branches += Statement.If.Branch(expression(condition), body(then))
			when (val otherwise = current.`else`) {
				null -> return Statement.If(branches, null)
				is KtIfExpression -> current = otherwise
				else -> return Statement.If(branches, body(otherwise))
			}
		}
	}

	/** A loop over a range of integers, as a numeric `for`, counting from 1 when the file's loops allow it. */
	private fun forLoop(loop: KtForExpression): Statement {
		val parameter = loop.loopParameter ?: throw Rejection.notYet(loop, "A for loop that destructures")
		val range = CountingRange.of(loop) ?: throw Rejection.notYet(loop, "A for loop over anything but a range")
		val name = unit.names.luaName(parameter.name!!)
		val start = expression(range.start)
		val end = expression(range.end)
		val body = body(loop.body ?: throw Rejection(loop, "A for loop without a body does not compile."))
		return if (parameter in unit.oneBased) {
			// Lua's variable is Kotlin's plus one, so its range is one higher
			val last = if (range.inclusive) IndexArithmetic.plusOne(end) else end
			Statement.NumericFor(name, IndexArithmetic.plusOne(start), last, null, body)
		} else {
			val last = if (range.inclusive) end else IndexArithmetic.minusOne(end)
			Statement.NumericFor(name, start, last, null, body)
		}
	}

	// ---- Expressions

	fun expression(expression: KtExpression): Expression = when (expression) {
		is KtConstantExpression -> constant(expression)
		is KtStringTemplateExpression -> string(expression)
		is KtNameReferenceExpression -> reference(expression, null)
		is KtDotQualifiedExpression -> qualified(expression)
		is KtCallExpression -> call(expression, null)
		is KtThisExpression -> self(expression)
		is KtParenthesizedExpression -> expression(expression.expression ?: throw Rejection(expression, EMPTY))
		is KtPrefixExpression -> prefix(expression)
		is KtBinaryExpression -> binary(expression)
		is KtArrayAccessExpression -> index(expression)
		else -> throw Rejection.notYet(expression, "This kind of expression")
	}

	private fun constant(constant: KtConstantExpression): Expression = when (constant.node.elementType) {
		KtNodeTypes.NULL -> Expression.Nil()
		KtNodeTypes.BOOLEAN_CONSTANT -> Expression.BooleanLiteral(constant.text == "true")
		KtNodeTypes.INTEGER_CONSTANT -> integer(constant)
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
			return Expression.string(template.entries.joinToString("") { literalText(it) })
		}
		val format = StringBuilder()
		val arguments = mutableListOf<Expression>()
		for (entry in template.entries) {
			when (entry) {
				is KtSimpleNameStringTemplateEntry, is KtBlockStringTemplateEntry -> {
					format.append("{}")
					arguments += formatArgument(entry.expression ?: throw Rejection(entry, EMPTY))
				}
				else -> format.append(literalText(entry).replace("{", "{{").replace("}", "}}"))
			}
		}
		unit.useGlobal("formatStr")
		return Expression.Call(Expression.name("formatStr"), listOf(Expression.string(format.toString())) + arguments)
	}

	private fun literalText(entry: KtStringTemplateEntry): String = when (entry) {
		is KtEscapeStringTemplateEntry -> entry.unescapedValue
		is KtLiteralStringTemplateEntry -> entry.text
		else -> throw IllegalStateException("not literal text: ${entry.text}")
	}

	/**
	 * A value inside `formatStr`: strings, numbers, booleans and nil as they are; a `Long` through `tostring`,
	 * because the client would round it to a double.
	 */
	private fun formatArgument(value: KtExpression): Expression {
		val type = with(unit.session) { value.expressionType } ?: throw Rejection(value, EMPTY)
		return when {
			types.isLong(type) -> Expression.call(Expression.name("tostring"), expression(value))
			types.isString(type) || types.isIntegral(type) || types.isFloating(type) || types.isBoolean(type) ->
				expression(value)
			else -> throw Rejection.notYet(value, "A value of this type in a string template")
		}
	}

	/** `this`: the object a method runs on, `self`. */
	private fun self(where: KtThisExpression): Expression {
		if (!instance) {
			throw Rejection(where, if (owner != null && owner == unit.entry) "The plugin class becomes main.lua, whose"
				+ " members are locals with no object to pass around; pass what is needed instead of this."
				else "This code runs on no object, so there is no this.")
		}
		return Expression.name("self")
	}

	/** `receiver.member` or `receiver.member(...)`. */
	private fun qualified(qualified: KtDotQualifiedExpression): Expression {
		val receiver = qualified.receiverExpression
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
				is KaLocalVariableSymbol, is KaValueParameterSymbol ->
					Expression.name(unit.names.luaName(reference.getReferencedName()))
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
		val classId = symbol.callableId?.classId
		if (classId != null && classId.packageFqName.asString().startsWith("kotlin")) {
			return LibraryCalls.property(this@BodyLowering, where, symbol, receiver)
		}
		val declaration = symbol.psi
		val name = reference.getReferencedName()
		if (declaration is KtProperty && declaration.parent is KtFile) {
			if (declaration.containingFile != unit.file) {
				throw Rejection.notYet(where, "Reading a property of another file")
			}
			return Expression.name(unit.names.luaName(name))
		}
		val ownerClass = classId?.let { findClass(it) } ?: throw Rejection.notYet(where, "Reading $name")
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
			val local = declaration is KtProperty && declaration.hasModifier(KtTokens.PRIVATE_KEYWORD) &&
				declaration.containingFile == unit.file
			if (local) {
				return Expression.name(unit.names.luaName(name))
			}
			return Expression.field(staticTable(ownerClass), name)
		}
		return Expression.field(receiverOrSelf(receiver, where), name)
	}

	/** The table an object's members belong to: its own, or for a companion object its class's. */
	private fun staticTable(ownerClass: KaClassSymbol): Expression = with(unit.session) {
		if (ownerClass.classKind == KaClassKind.COMPANION_OBJECT) {
			unit.moduleReference(findClass(ownerClass.classId!!.outerClassId!!)!!)
		} else {
			unit.moduleReference(ownerClass)
		}
	}

	/** The object a member is read on: the receiver as written, or `self` for a member of the object itself. */
	fun receiverOrSelf(receiver: KtExpression?, where: KtElement): Expression {
		if (receiver != null) {
			return expression(receiver)
		}
		if (!instance) {
			throw Rejection.notYet(where, "Reading a member of an object here")
		}
		return Expression.name("self")
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
		val arguments = arguments(resolved, call)
		if (symbol is KaConstructorSymbol) {
			val type = symbol.containingClassId?.let { findClass(it) }
				?: throw IllegalStateException("a constructor outside a class: ${call.text}")
			if (api.isApi(type)) {
				return api.construct(where, symbol, type, arguments)
			}
			if (!unit.isPluginType(type)) {
				throw Rejection.notYet(where, "Creating a ${type.classId?.shortClassName}")
			}
			return Expression.Call(Expression.field(unit.moduleReference(type), "new"), arguments)
		}
		if (symbol !is KaNamedFunctionSymbol) {
			throw Rejection.notYet(where, "Calling this")
		}
		val ownerClass = api.owner(symbol) ?: throw Rejection.notYet(where, "Calling a function outside a class")
		if (api.isApi(ownerClass)) {
			checkSubscription(symbol, receiver, where)
			val target = if (symbol.isStatic) null else receiverOrSelf(receiver, where)
			return api.call(where, symbol, ownerClass, target, arguments)
		}
		if (!unit.isPluginType(ownerClass)) {
			return LibraryCalls.call(where, symbol)
		}
		return pluginCall(where, symbol, ownerClass, receiver, arguments)
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
		val name = function.name.asString()
		val ownerDeclaration = ownerClass.psi as? KtClassOrObject
		val declaration = function.psi
		val isPrivate = declaration is KtNamedFunction && declaration.hasModifier(KtTokens.PRIVATE_KEYWORD) &&
			declaration.containingFile == unit.file
		return when {
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
				listOf(receiverOrSelf(receiver, call)) + arguments)
			else -> Expression.MethodCall(receiverOrSelf(receiver, call), name, arguments)
		}
	}

	/** A call's arguments in the order of the parameters they go to; a vararg's values one by one. */
	private fun arguments(call: KaFunctionCall<*>, where: KtCallExpression): List<Expression> {
		val byParameter = call.valueArgumentMapping.entries.groupBy({ it.value.symbol }, { it.key })
		val arguments = mutableListOf<Expression>()
		for (parameter in call.symbol.valueParameters) {
			val values = byParameter[parameter]
			if (values == null) {
				if (parameter.isVararg) {
					continue
				}
				throw Rejection.notYet(where, "Leaving out an argument that has a default")
			}
			values.sortedBy { it.textOffset }.forEach { arguments += expression(it) }
		}
		return arguments
	}

	private fun prefix(prefix: KtPrefixExpression): Expression {
		val operand = expression(prefix.baseExpression ?: throw Rejection(prefix, EMPTY))
		return when (prefix.operationToken) {
			KtTokens.EXCL -> Expression.Unary(UnaryOperator.NOT, operand)
			KtTokens.MINUS -> Expression.Unary(UnaryOperator.NEGATE, operand)
			else -> throw Rejection.notYet(prefix, "The prefix operator ${prefix.operationReference.text}")
		}
	}

	private fun binary(binary: KtBinaryExpression): Expression {
		val left = binary.left ?: throw Rejection(binary, EMPTY)
		val right = binary.right ?: throw Rejection(binary, EMPTY)
		val operator = when (binary.operationToken) {
			KtTokens.EQEQ -> BinaryOperator.EQUAL
			KtTokens.EXCLEQ -> BinaryOperator.NOT_EQUAL
			KtTokens.LT -> BinaryOperator.LESS
			KtTokens.GT -> BinaryOperator.GREATER
			KtTokens.LTEQ -> BinaryOperator.LESS_EQUAL
			KtTokens.GTEQ -> BinaryOperator.GREATER_EQUAL
			KtTokens.ANDAND -> BinaryOperator.AND
			KtTokens.OROR -> BinaryOperator.OR
			KtTokens.PLUS -> {
				// In a loop that counts from 1, Lua's variable is already Kotlin's plus one
				oneBasedPlusOne(left, right)?.let { return it }
				BinaryOperator.ADD
			}
			KtTokens.MINUS -> BinaryOperator.SUBTRACT
			KtTokens.MUL -> BinaryOperator.MULTIPLY
			in ASSIGNMENTS -> throw Rejection(binary, "In Lua an assignment is a statement, not a value; assign on a"
				+ " line of its own.")
			else -> throw Rejection.notYet(binary, "The operator ${binary.operationReference.text}")
		}
		return Expression.Binary(operator, expression(left), expression(right))
	}

	/** `i + 1` or `1 + i` where `i` counts from 1 in Lua: the variable itself. */
	private fun oneBasedPlusOne(left: KtExpression, right: KtExpression): Expression? {
		for ((variable, other) in listOf(left to right, right to left)) {
			if (oneBasedVariable(variable) && other is KtConstantExpression && other.text == "1") {
				return expression(variable)
			}
		}
		return null
	}

	/** `list[i]`, the element at a position Kotlin counts from 0 and Lua from 1. */
	private fun index(access: KtArrayAccessExpression): Expression {
		val receiver = access.arrayExpression ?: throw Rejection(access, EMPTY)
		val position = access.indexExpressions.singleOrNull()
			?: throw Rejection.notYet(access, "Indexing with several values")
		val receiverType = with(unit.session) { receiver.expressionType }
		if (receiverType == null || !types.isList(receiverType)) {
			throw Rejection.notYet(access, "Indexing this type")
		}
		val key = expression(position).let { if (oneBasedVariable(position)) it else IndexArithmetic.plusOne(it) }
		return Expression.Index(expression(receiver), key)
	}

	/** Whether an expression names the variable of a loop that counts from 1. */
	private fun oneBasedVariable(expression: KtExpression): Boolean {
		if (expression !is KtNameReferenceExpression) {
			return false
		}
		val declaration = with(unit.session) { expression.resolveSymbol()?.psi }
		return declaration is KtParameter && declaration in unit.oneBased
	}

	private companion object {
		const val EMPTY = "This expression is incomplete and does not compile."
		val ASSIGNMENTS = setOf(KtTokens.EQ, KtTokens.PLUSEQ, KtTokens.MINUSEQ, KtTokens.MULTEQ, KtTokens.DIVEQ,
			KtTokens.PERCEQ)
		val STEPS = setOf(KtTokens.PLUSPLUS, KtTokens.MINUSMINUS)
	}
}
