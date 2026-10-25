package org.adelheid.oneiro.kotlin

import org.adelheid.oneiro.lowering.ClientApi
import org.adelheid.oneiro.lowering.LuaModule
import org.adelheid.oneiro.lowering.SettingsFunctions
import org.adelheid.oneiro.lua.Block
import org.adelheid.oneiro.lua.Comment
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.FunctionBody
import org.adelheid.oneiro.lua.Statement
import org.adelheid.oneiro.lua.Trivia
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtReturnExpression

/**
 * An interface annotated `@ConfigGroup` as a module of functions over the client's `PersistentDB`. A getter, a
 * function without parameters whose body is its default value, reads the setting with the typed accessor of its type
 * and gives the default where nothing is stored. A setter, a function with one parameter and no body, stores it. Each
 * setting is kept under `"<group>.<keyName>"`.
 */
internal class SettingsLowering(private val unit: FileLowering, private val type: KtClass) {

	fun lower(header: List<Comment>, path: String): LuaModule = with(unit.session) {
		val group = unit.annotations.value(type.symbol, Annotations.CONFIG_GROUP)!!
		val lines = header.toMutableList()
		if (lines.isNotEmpty()) {
			lines += Comment.line("")
		}
		lines += SettingsFunctions.storageNote(group)
		unit.useGlobal(ClientApi.PERSISTENT_DB)
		val members = mutableListOf<Member>()
		for (member in type.declarations) {
			try {
				if (member !is KtNamedFunction) {
					throw Rejection(member, "An interface of settings holds only its settings' getters and setters,"
						+ " each with @ConfigItem.")
				}
				members += setting(member, group)
			} catch (rejection: Rejection) {
				unit.reject(rejection)
			}
		}
		return LuaModule.of(path, lines, Block(unit.assemble(Expression.Table(listOf()), false, members), listOf()))
	}

	/** One getter or setter of a setting, as a function of the settings module. */
	private fun setting(function: KtNamedFunction, group: String): Member = with(unit.session) {
		val symbol = function.symbol
		val keyName = unit.annotations.argument(symbol, Annotations.CONFIG_ITEM, "keyName")
			?: throw Rejection(function, "Every function of an interface of settings is a setting's getter or setter,"
				+ " with @ConfigItem(keyName = ..., name = ...).")
		val name = unit.annotations.argument(symbol, Annotations.CONFIG_ITEM, "name").orEmpty()
		val description = unit.annotations.argument(symbol, Annotations.CONFIG_ITEM, "description").orEmpty()
		val key = "$group.$keyName"
		val lines = unit.source.leading(function).toMutableList()
		lines += SettingsFunctions.itemComment(name, description)
		function.docComment?.let { doc -> KDocText.description(doc).forEach { lines += Comment.line(it) } }
		val body: FunctionBody
		if (function.hasBody()) {
			val default = defaultValue(function)
			val returned = symbol.returnType
			val kind = kind(returned, function)
			lines += Comment.annotation("@return " + unit.types.luaType(returned).removeSuffix("?"))
			val enumTable = pluginEnum(returned)?.let(unit::moduleReference)
			val fallback = BodyLowering(unit, null, false).expression(default)
			body = FunctionBody.of(listOf(), SettingsFunctions.getter(key, kind, fallback, enumTable))
		} else {
			val parameter = function.valueParameters.singleOrNull()
			if (parameter == null || !symbol.returnType.isUnitType) {
				throw Rejection(function, "A setting's setter is a function with one parameter, the new value, and no"
					+ " body, such as fun setCount(count: Int); a getter is a function whose body is the default"
					+ " value.")
			}
			val local = unit.names.luaName(parameter.name!!)
			val parameterType = parameter.symbol.returnType
			lines += Comment.annotation("@param $local " + unit.types.luaType(parameterType).removeSuffix("?"))
			body = FunctionBody.of(listOf(local), SettingsFunctions.setter(key, kind(parameterType, parameter),
				Expression.name(local)))
		}
		val declaration = Statement.FunctionDeclaration(listOf(type.name!!, function.name!!), null, body)
		return Member(listOf(function), true, null,
			listOf(Block.Entry(declaration, Trivia(true, lines, null), unit.source.position(unit.codeStart(function)))),
			listOf(), References.of(unit, function))
	}

	/** A getter's default: its expression body, or the one value its block returns. */
	private fun defaultValue(function: KtNamedFunction): KtExpression {
		val body = function.bodyExpression
		val returned = (body as? KtBlockExpression)?.statements?.singleOrNull() as? KtReturnExpression
		val value = if (body is KtBlockExpression) returned?.returnedExpression else body
		if (value == null || function.valueParameters.isNotEmpty()) {
			throw Rejection(function, "A setting's getter is a function without parameters whose body is the default"
				+ " value, such as fun count(): Int = 5.")
		}
		return value
	}

	private fun kind(type: KaType, where: KtElement): SettingsFunctions.Kind = when {
		unit.types.isBoolean(type) -> SettingsFunctions.Kind.BOOL
		unit.types.isIntegral(type) -> SettingsFunctions.Kind.INT
		unit.types.isFloating(type) -> SettingsFunctions.Kind.FLOAT
		unit.types.isString(type) || pluginEnum(type) != null -> SettingsFunctions.Kind.STRING
		else -> throw Rejection(where, "A setting is a Boolean, an Int or Long, a Double, a String or an enum of the"
			+ " plugin's; ${ClientApi.PERSISTENT_DB} keeps those with typed accessors.")
	}

	/** The plugin's enum a setting holds, by its constants' names; null for any other type. */
	private fun pluginEnum(type: KaType): KaClassSymbol? = with(unit.session) {
		val symbol = type.expandedSymbol ?: return null
		if (symbol.classKind == KaClassKind.ENUM_CLASS && unit.isPluginType(symbol)) symbol else null
	}
}
