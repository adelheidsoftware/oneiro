package org.adelheid.oneiro.kotlin.core

import org.adelheid.oneiro.lowering.translation.DocAnnotations
import org.adelheid.oneiro.lua.BinaryOperator
import org.adelheid.oneiro.lua.Block
import org.adelheid.oneiro.lua.Comment
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.FunctionBody
import org.adelheid.oneiro.lua.Statement
import org.adelheid.oneiro.lua.Trivia
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty

/**
 * Kotlin functions as Lua functions, with their comments: an object's, as fields of its table or as locals; a class's
 * methods, called with a colon, or as locals that take the object first; and the plugin class's, which are all locals
 * of `main.lua`.
 */
internal class Functions(private val unit: FileLowering) {

	/**
	 * A function of an object or companion object: `function Table.name(...)`, or a local when it is private. An
	 * extension function takes its receiver first, as `self`: `function Texts.shout(self)`.
	 */
	fun static(function: KtNamedFunction, table: String, isPrivate: Boolean): Member {
		val body = body(function, null, false, receiver(function))
		return if (isPrivate) {
			local(function, body)
		} else {
			field(function, listOf(table, unit.memberName(function)), null, body)
		}
	}

	/** A method: `function Table:name(...)`, or `local function name(self, ...)` when it is private. */
	fun method(function: KtNamedFunction, table: String, isPrivate: Boolean): Member {
		if (function.receiverTypeReference != null) {
			throw Rejection.notYet(function, "An extension function inside a class with objects")
		}
		val owner = function.parent.parent as KtClassOrObject
		return if (isPrivate) {
			local(function, body(function, owner, true, listOf("self")))
		} else {
			field(function, listOf(table), unit.memberName(function), body(function, owner, true, listOf()))
		}
	}

	/** A function of an enum constant's body, which replaces the enum's: `function Herb.RANARR:describe()`. */
	fun constantMethod(function: KtNamedFunction, path: List<String>): Member {
		val owner = function.parent.parent as KtClassOrObject
		return field(function, path, unit.memberName(function), body(function, owner, true, listOf()))
	}

	/**
	 * A function of the plugin class: a local of `main.lua`, which takes its own parameters, or those given for a
	 * function the client calls with others.
	 */
	fun entryFunction(function: KtNamedFunction, owner: KtClassOrObject, parameters: List<String>? = null): Member {
		val lowering = BodyLowering(unit, owner, false)
		if (parameters != null) {
			return local(function, FunctionBody.of(parameters, functionBlock(function, lowering)))
		}
		return local(function, functionBody(function, receiver(function), lowering))
	}

	/**
	 * The parameter an extension function takes its receiver in, `self`, which its `this` stands for; none for any
	 * other function.
	 */
	private fun receiver(function: KtNamedFunction): List<String> {
		if (function.receiverTypeReference == null) {
			return listOf()
		}
		unit.aliasReceiver(function, Expression.name("self"))
		return listOf("self")
	}

	/**
	 * An extension property, as a function that takes the receiver as `self` and gives the getter's value:
	 * `val String.initial: String get() = take(1)` is `function Texts.initial(self)`, or a local function when it is
	 * private. A setter is not supported yet.
	 */
	fun extensionGetter(property: KtProperty, table: String, isPrivate: Boolean): Member {
		val getter = property.getter?.takeIf { it.hasBody() } ?: throw Rejection(property, "An extension property"
			+ " holds no value of its own; give it a getter, get() = ...")
		property.setter?.let { throw Rejection.notYet(it, "An extension property's setter") }
		// The receiver is the property's, which its getter's this names
		unit.aliasReceiver(property, Expression.name("self"))
		unit.aliasReceiver(getter, Expression.name("self"))
		val lowering = BodyLowering(unit, null, false)
		val block = getter.bodyBlockExpression?.let { lowering.block(it, endsFunction = true) }
			?: Block(lowering.expressionBody(getter.bodyExpression!!, false), listOf())
		val body = FunctionBody.of(listOf("self"), block)
		val comments = getterComments(property)
		return if (isPrivate) local(property, comments, body)
		else field(property, comments, listOf(table, unit.memberName(property)), null, body)
	}

	/** `local function name(...)`, which `function name(...)` assigns when it is declared ahead. */
	private fun local(function: KtNamedFunction, body: FunctionBody): Member = local(function, comments(function), body)

	private fun local(declaration: KtCallableDeclaration, comments: List<Comment>, body: FunctionBody): Member {
		val name = unit.names.luaName(declaration.name!!)
		val trivia = Trivia(true, comments, null)
		val origin = unit.source.position(unit.codeStart(declaration))
		val declared = Block.Entry(Statement.LocalFunction(name, body), trivia, origin)
		val assigned = Block.Entry(Statement.FunctionDeclaration(listOf(name), null, body), trivia, origin)
		return Member(listOf(declaration), true, name, listOf(declared), listOf(assigned),
			References.of(unit, declaration))
	}

	private fun field(function: KtNamedFunction, path: List<String>, method: String?, body: FunctionBody): Member =
		field(function, comments(function), path, method, body)

	private fun field(declaration: KtCallableDeclaration, comments: List<Comment>, path: List<String>, method: String?,
		body: FunctionBody): Member {
		val trivia = Trivia(true, comments, null)
		val origin = unit.source.position(unit.codeStart(declaration))
		val statement = Block.Entry(Statement.FunctionDeclaration(path, method, body), trivia, origin)
		return Member(listOf(declaration), true, null, listOf(statement), listOf(), References.of(unit, declaration))
	}

	/**
	 * A function's parameters and body, the parameters after the given first ones: `self` for a local that a method
	 * becomes.
	 */
	private fun body(function: KtNamedFunction, owner: KtClassOrObject?, instance: Boolean, first: List<String>):
		FunctionBody = functionBody(function, first, BodyLowering(unit, owner, instance))

	/**
	 * A function's Lua parameters after the given first ones, and its block. A `vararg` parameter is Lua's `...`,
	 * which the block first gathers into a local of the parameter's name: `local numbers = { ... }`.
	 */
	private fun functionBody(function: KtNamedFunction, first: List<String>, lowering: BodyLowering): FunctionBody {
		val parameters = first.toMutableList()
		var gathered: String? = null
		for (parameter in function.valueParameters) {
			val name = unit.localName(parameter, parameter.name!!)
			val type = with(unit.session) { parameter.symbol.returnType.expandedSymbol }
			if (type != null && unit.annotations.has(type, Annotations.LUA_RETURN_VALUES)) {
				throw Rejection(parameter, "${type.classId?.shortClassName} stands for several Lua values, which only a"
					+ " local can receive, from a call; pass its components one by one instead.")
			}
			if (!parameter.isVarArg) {
				parameters += name
			} else if (parameter == function.valueParameters.last()) {
				gathered = name
			} else {
				throw Rejection(parameter, "In Lua only the last parameter takes any number of values; make the vararg"
					+ " parameter the last one.")
			}
		}
		val block = functionBlock(function, lowering)
		val prologue = defaults(function.valueParameters, lowering).toMutableList()
		if (gathered != null) {
			val values = Expression.Table(listOf(Expression.TableField.Positional(Expression.Vararg())))
			prologue += Statement.Local(listOf(gathered), listOf(values))
		}
		val entries = prologue.map(Block.Entry::of) + block.entries()
		return FunctionBody(parameters, gathered != null, Block(entries, block.closingComments()))
	}

	/**
	 * The statements that give the parameters a call leaves out their defaults: `amount = amount or STEP`, or for a
	 * `Boolean`, which `or` would not leave false, `if negative == nil then negative = false end`. A default of null
	 * leaves the parameter nil.
	 *
	 * @param given the defaults that a primary constructor's parameters take from the secondary constructors
	 */
	fun defaults(parameters: List<KtParameter>, lowering: BodyLowering,
		given: Map<KtParameter, KtExpression> = mapOf()): List<Statement> = with(unit.session) {
		val statements = mutableListOf<Statement>()
		for (parameter in parameters) {
			val default = parameter.defaultValue ?: given[parameter] ?: continue
			if (default is KtConstantExpression && default.node.elementType == KtNodeTypes.NULL) {
				continue
			}
			val type = parameter.symbol.returnType
			if (unit.types.isNullable(type)) {
				throw Rejection(default, "Lua cannot tell a null argument from one left out, so a parameter that may be"
					+ " null takes no default but null.")
			}
			val name = Expression.name(unit.localName(parameter, parameter.name!!))
			val value = lowering.expression(default)
			statements += if (unit.types.isBoolean(type)) {
				val absent = Expression.binary(name, BinaryOperator.EQUAL, Expression.Nil())
				Statement.ifThen(absent, Block.of(Statement.assignment(name, value)))
			} else {
				Statement.assignment(name, Expression.binary(name, BinaryOperator.OR, value))
			}
		}
		statements
	}

	/** A function's statements: its block, or `return` of its expression body. */
	fun functionBlock(function: KtNamedFunction, lowering: BodyLowering): Block {
		val block = function.bodyBlockExpression
		val expression = function.bodyExpression
		return when {
			block != null -> lowering.block(block, endsFunction = true)
			// An expression body is the function's return, which a chain, a when or a scope function can be too
			expression != null -> Block(lowering.expressionBody(expression, returnsUnit(function)), listOf())
			else -> throw Rejection(function, "A function without a body has no Lua; give it one.")
		}
	}

	private fun returnsUnit(function: KtNamedFunction): Boolean =
		with(unit.session) { function.symbol.returnType.isUnitType }

	/**
	 * The `---@param` annotations of parameters, with their KDoc texts: a parameter with a default is optional,
	 * `amount?`, and a vararg parameter is `...`, of the type of each value it takes.
	 *
	 * @param optional the parameters that take a default from elsewhere, as from a secondary constructor
	 */
	fun parameterAnnotations(parameters: List<KtParameter>, doc: KDoc, optional: Set<KtParameter> = setOf()):
		List<Comment> = with(unit.session) {
		parameters.map { parameter ->
			val type = unit.types.luaType(parameter.symbol.returnType)
			val text = KDocText.param(doc, parameter.name!!)
			val name = when {
				parameter.isVarArg -> "..."
				parameter.hasDefaultValue() || parameter in optional ->
					unit.localName(parameter, parameter.name!!) + "?"
				else -> unit.localName(parameter, parameter.name!!)
			}
			Comment.annotation("@param $name $type $text".trimEnd())
		}
	}

	/**
	 * An extension property's comments: the source comments above it, its KDoc description, and, with KDoc, its
	 * receiver as `---@param self` and its value as `---@return`.
	 */
	private fun getterComments(property: KtProperty): List<Comment> = with(unit.session) {
		val lines = unit.source.leading(property).toMutableList()
		val doc = property.docComment ?: return lines
		KDocText.description(doc).forEach { lines += Comment.line(it) }
		property.symbol.receiverParameter?.let { receiver ->
			lines += Comment.annotation("@param self ${unit.types.luaType(receiver.returnType)}")
		}
		lines += Comment.annotation(DocAnnotations.returned(unit.types.luaType(property.symbol.returnType),
			KDocText.returned(doc)))
		return lines
	}

	/**
	 * A function's comments: the source comments above it, its KDoc description, and, with KDoc, the annotations its
	 * signature gives, `---@param` and `---@return`.
	 */
	fun comments(function: KtNamedFunction): List<Comment> = with(unit.session) {
		val lines = unit.source.leading(function).toMutableList()
		val doc = function.docComment ?: return lines
		KDocText.description(doc).forEach { lines += Comment.line(it) }
		function.symbol.receiverParameter?.let { receiver ->
			lines += Comment.annotation("@param self ${unit.types.luaType(receiver.returnType)}")
		}
		lines += parameterAnnotations(function.valueParameters, doc)
		val returned = function.symbol.returnType
		val values = returned.expandedSymbol?.takeIf { unit.annotations.has(it, Annotations.LUA_RETURN_VALUES) }
		if (values != null) {
			// Several values: one annotation per value, named after it
			for (value in values.declaredMemberScope.constructors.first().valueParameters) {
				lines += Comment.annotation("@return ${unit.types.luaType(value.returnType)} ${value.name.asString()}")
			}
		} else if (!returned.isUnitType) {
			lines += Comment.annotation(DocAnnotations.returned(unit.types.luaType(returned), KDocText.returned(doc)))
		}
		return lines
	}
}
