package org.adelheid.oneiro.kotlin

import org.adelheid.oneiro.lowering.DocAnnotations
import org.adelheid.oneiro.lua.Block
import org.adelheid.oneiro.lua.Comment
import org.adelheid.oneiro.lua.FunctionBody
import org.adelheid.oneiro.lua.Statement
import org.adelheid.oneiro.lua.Trivia
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Kotlin functions as Lua functions, with their comments: an object's, as fields of its table or as locals; a class's
 * methods, called with a colon, or as locals that take the object first; and the plugin class's, which are all locals
 * of `main.lua`.
 */
internal class Functions(private val unit: FileLowering) {

	/** A function of an object or companion object: `function Table.name(...)`, or a local when it is private. */
	fun static(function: KtNamedFunction, table: String, isPrivate: Boolean): Member {
		val body = body(function, null, false, listOf())
		return if (isPrivate) local(function, body) else field(function, listOf(table, function.name!!), null, body)
	}

	/** A method: `function Table:name(...)`, or `local function name(self, ...)` when it is private. */
	fun method(function: KtNamedFunction, table: String, isPrivate: Boolean): Member {
		val owner = function.parent.parent as KtClassOrObject
		return if (isPrivate) {
			local(function, body(function, owner, true, listOf("self")))
		} else {
			field(function, listOf(table), function.name!!, body(function, owner, true, listOf()))
		}
	}

	/** A function of the plugin class: a local of `main.lua`, which takes the parameters given. */
	fun entryFunction(function: KtNamedFunction, owner: KtClassOrObject, parameters: List<String>): Member {
		val lowering = BodyLowering(unit, owner, false)
		return local(function, FunctionBody.of(parameters, functionBlock(function, lowering)))
	}

	/** `local function name(...)`, which `function name(...)` assigns when it is declared ahead. */
	private fun local(function: KtNamedFunction, body: FunctionBody): Member {
		val name = unit.names.luaName(function.name!!)
		val trivia = Trivia(true, comments(function), null)
		val origin = unit.source.position(unit.codeStart(function))
		val declared = Block.Entry(Statement.LocalFunction(name, body), trivia, origin)
		val assigned = Block.Entry(Statement.FunctionDeclaration(listOf(name), null, body), trivia, origin)
		return Member(listOf(function), true, name, listOf(declared), listOf(assigned), References.of(unit, function))
	}

	private fun field(function: KtNamedFunction, path: List<String>, method: String?, body: FunctionBody): Member {
		val trivia = Trivia(true, comments(function), null)
		val origin = unit.source.position(unit.codeStart(function))
		val declaration = Block.Entry(Statement.FunctionDeclaration(path, method, body), trivia, origin)
		return Member(listOf(function), true, null, listOf(declaration), listOf(), References.of(unit, function))
	}

	/**
	 * A function's parameters and body, the parameters after the given first ones: `self` for a local that a method
	 * becomes.
	 */
	private fun body(function: KtNamedFunction, owner: KtClassOrObject?, instance: Boolean, first: List<String>):
		FunctionBody {
		val lowering = BodyLowering(unit, owner, instance)
		val parameters = first + function.valueParameters.map { unit.names.luaName(it.name!!) }
		return FunctionBody.of(parameters, functionBlock(function, lowering))
	}

	/** A function's statements: its block, or `return` of its expression body. */
	fun functionBlock(function: KtNamedFunction, lowering: BodyLowering): Block {
		val block = function.bodyBlockExpression
		val expression = function.bodyExpression
		return when {
			block != null -> lowering.block(block)
			expression != null && returnsUnit(function) ->
				Block(lowering.expressionStatement(expression).map(Block.Entry::of), listOf())
			expression != null -> Block.of(Statement.Return(listOf(lowering.expression(expression))))
			else -> throw Rejection(function, "A function without a body has no Lua; give it one.")
		}
	}

	private fun returnsUnit(function: KtNamedFunction): Boolean =
		with(unit.session) { function.symbol.returnType.isUnitType }

	/**
	 * A function's comments: the source comments above it, its KDoc description, and, with KDoc, the annotations its
	 * signature gives, `---@param` and `---@return`.
	 */
	fun comments(function: KtNamedFunction): List<Comment> = with(unit.session) {
		val lines = unit.source.leading(function).toMutableList()
		val doc = function.docComment ?: return lines
		KDocText.description(doc).forEach { lines += Comment.line(it) }
		for (parameter in function.valueParameters) {
			val type = unit.types.luaType(parameter.symbol.returnType)
			val text = KDocText.param(doc, parameter.name!!)
			lines += Comment.annotation("@param ${unit.names.luaName(parameter.name!!)} $type $text".trimEnd())
		}
		val returned = function.symbol.returnType
		if (!returned.isUnitType) {
			lines += Comment.annotation(DocAnnotations.returned(unit.types.luaType(returned), KDocText.returned(doc)))
		}
		return lines
	}
}
