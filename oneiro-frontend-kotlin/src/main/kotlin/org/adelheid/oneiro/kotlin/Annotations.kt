package org.adelheid.oneiro.kotlin

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotation
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaAnnotatedSymbol
import org.jetbrains.kotlin.name.ClassId

/**
 * Oneiro's annotations, as the Analysis API shows them on the declarations a plugin uses: the RS3 bindings' Lua
 * shapes, the plugin model's markers, and the settings annotations.
 */
internal class Annotations(private val session: KaSession) {

	fun has(symbol: KaSymbol, annotation: ClassId): Boolean = find(symbol, annotation) != null

	/** The annotation's `value`, or null when the symbol does not carry it. */
	fun value(symbol: KaSymbol, annotation: ClassId): String? = argument(symbol, annotation, "value")

	/** One string argument of the annotation, or null when the symbol does not carry it or it is not set. */
	fun argument(symbol: KaSymbol, annotation: ClassId, name: String): String? {
		val found = find(symbol, annotation) ?: return null
		val argument = found.arguments.firstOrNull { it.name.asString() == name }?.expression ?: return null
		return (argument as? KaAnnotationValue.ConstantValue)?.value?.value?.toString()
	}

	/** Whether the symbol carries the annotation with the argument set, whatever its kind of value. */
	fun hasArgument(symbol: KaSymbol, annotation: ClassId, name: String): Boolean =
		find(symbol, annotation)?.arguments?.any { it.name.asString() == name } == true

	private fun find(symbol: KaSymbol, annotation: ClassId): KaAnnotation? = with(session) {
		(symbol as? KaAnnotatedSymbol)?.annotations?.firstOrNull { it.classId == annotation }
	}

	companion object {

		private fun api(name: String) = ClassId.fromString("org/adelheid/oneiro/api/$name")

		val LUA_GLOBAL = api("LuaGlobal")
		val LUA_TYPE = api("LuaType")
		val LUA_NAME = api("LuaName")
		val LUA_COLON = api("LuaColon")
		val LUA_DOT = api("LuaDot")
		val LUA_CONSTANTS = api("LuaConstants")
		val LUA_EVENT = api("LuaEvent")
		val LUA_PERMISSION = api("LuaPermission")
		val LUA_MANIFEST_FLAG = api("LuaManifestFlag")
		val LUA_DEV_ONLY = api("LuaDevOnly")
		val LUA_REVIEW = api("LuaReview")
		val LUA_MODULE = api("LuaModule")
		val LUA_OPTIONAL = api("LuaOptional")
		val LUA_RETURN_VALUES = api("LuaReturnValues")
		val LUA_FROM_ID = api("LuaFromId")
		val LUA_SAME = api("LuaSame")
		val LUA_INDEX = api("LuaIndex")
		val LUA_OPERATOR = api("LuaOperator")
		val LUA_COPY = api("LuaCopy")
		val SUBSCRIBE = api("Subscribe")
		val CONFIG_GROUP = api("ConfigGroup")
		val CONFIG_ITEM = api("ConfigItem")
		val PLUGIN = api("Plugin")
		val DEV_PLUGIN = api("DevPlugin")
	}
}
