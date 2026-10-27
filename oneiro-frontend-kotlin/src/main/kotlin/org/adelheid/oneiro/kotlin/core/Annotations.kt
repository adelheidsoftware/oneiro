package org.adelheid.oneiro.kotlin.core

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

		private fun binding(name: String) = ClassId.fromString("org/adelheid/oneiro/api/binding/$name")

		val LUA_GLOBAL = binding("LuaGlobal")
		val LUA_TYPE = binding("LuaType")
		val LUA_NAME = api("LuaName")
		val LUA_COLON = binding("LuaColon")
		val LUA_DOT = binding("LuaDot")
		val LUA_CONSTANTS = binding("LuaConstants")
		val LUA_EVENT = binding("LuaEvent")
		val LUA_PERMISSION = binding("LuaPermission")
		val LUA_MANIFEST_FLAG = binding("LuaManifestFlag")
		val LUA_DEV_ONLY = binding("LuaDevOnly")
		val LUA_REVIEW = binding("LuaReview")
		val LUA_MODULE = binding("LuaModule")
		val LUA_OPTIONAL = binding("LuaOptional")
		val LUA_RETURN_VALUES = api("LuaReturnValues")
		val LUA_FROM_ID = binding("LuaFromId")
		val LUA_SAME = binding("LuaSame")
		val LUA_INDEX = binding("LuaIndex")
		val LUA_OPERATOR = binding("LuaOperator")
		val LUA_COPY = binding("LuaCopy")
		val SUBSCRIBE = api("Subscribe")
		val CONFIG_GROUP = api("ConfigGroup")
		val CONFIG_ITEM = api("ConfigItem")
		val PLUGIN = api("Plugin")
		val DEV_PLUGIN = api("DevPlugin")
	}
}
