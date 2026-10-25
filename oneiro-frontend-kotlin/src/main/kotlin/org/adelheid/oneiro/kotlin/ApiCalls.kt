package org.adelheid.oneiro.kotlin

import com.intellij.psi.PsiElement
import org.adelheid.oneiro.api.LuaConstants
import org.adelheid.oneiro.lowering.ClientApi
import org.adelheid.oneiro.lua.BinaryOperator
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.LuaNames
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaEnumEntrySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaJavaFieldSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.psi.KtExpression

/**
 * Calls into Lua that Oneiro does not write, declared by classes annotated `@LuaGlobal` (a table, such as `Player` or
 * `ui.Text`) or `@LuaType` (values the Lua hands out, such as `NPCEntity`): the RS3 bindings, the bindings of the
 * plugin's own Lua, and the plugin's generated `Resources` and `Urls`. A table's path starts at a global, or for a
 * module's class, `@LuaModule`, at the local its `require` binds. Each member's annotations give its Lua shape, as in
 * the Java front end: a static method is `Table.Name(...)`, or `Table:Name(...)` with `@LuaColon`; an instance method
 * is `object:Name(...)`, or `object.Name(...)` with `@LuaDot`; `@LuaIndex` indexes the table, `@LuaSame` is the
 * receiver itself, and `@LuaOperator` is Lua's operator; a constructor is `Table.new(...)`, or `{}` for a `@LuaType`
 * table the plugin fills in. Members that need a permission the manifest does not declare, or that must never ship,
 * are rejected here, and members moderation reviews closely are warned about.
 */
internal class ApiCalls(private val unit: FileLowering) {

	private val annotations = unit.annotations

	/** Whether a class stands for Lua that Oneiro does not write. */
	fun isApi(owner: KaClassSymbol): Boolean =
		annotations.has(owner, Annotations.LUA_GLOBAL) || annotations.has(owner, Annotations.LUA_TYPE)

	/**
	 * The class that declares a member, a local class or an object expression among them; null for a function outside
	 * any class.
	 */
	fun owner(member: KaCallableSymbol): KaClassSymbol? = with(unit.session) {
		member.callableId?.classId?.let { findClass(it) } ?: member.containingSymbol as? KaClassSymbol
	}

	/** A call of a method: on the global table for a static one, or on the object. */
	fun call(where: PsiElement, method: KaNamedFunctionSymbol, owner: KaClassSymbol, receiver: Expression?,
		arguments: List<Expression>): Expression {
		checkUse(method, owner, where)
		val name = memberName(method, owner)
		if (annotations.has(method, Annotations.LUA_INDEX)) {
			val indexed = if (method.isStatic) table(owner, global(owner, where)) else receiver(receiver, where)
			return Expression.Index(indexed, arguments.single())
		}
		if (annotations.has(method, Annotations.LUA_SAME)) {
			// ui.Hook.ONCLICK.stoppable() only retypes the hook for Kotlin; in Lua it is the hook itself
			return receiver(receiver, where)
		}
		annotations.value(method, Annotations.LUA_OPERATOR)?.let { operator ->
			// a.plus(b) on the client's vectors is Lua's own a + b
			return Expression.binary(receiver(receiver, where), binaryOperator(operator), arguments.single())
		}
		if (method.isStatic) {
			val global = global(owner, where)
			if (global.isEmpty()) {
				unit.useGlobal(name)
				return Expression.Call(Expression.name(name), arguments)
			}
			val table = table(owner, global)
			return if (annotations.has(method, Annotations.LUA_COLON)) Expression.MethodCall(table, name, arguments)
			else Expression.Call(Expression.field(table, name), arguments)
		}
		val target = receiver(receiver, where)
		return if (annotations.has(method, Annotations.LUA_DOT)) {
			Expression.Call(Expression.field(target, name), arguments)
		} else {
			Expression.MethodCall(target, name, arguments)
		}
	}

	private fun receiver(receiver: Expression?, where: PsiElement): Expression =
		receiver ?: throw IllegalStateException("an API method without a receiver: ${where.text}")

	private fun binaryOperator(operator: String): BinaryOperator = when (operator) {
		"+" -> BinaryOperator.ADD
		"-" -> BinaryOperator.SUBTRACT
		"*" -> BinaryOperator.MULTIPLY
		"/" -> BinaryOperator.DIVIDE
		else -> throw IllegalStateException("an operator the bindings should not give: $operator")
	}

	/**
	 * Assigning a field of a value the client hands out as a copy, `npc.coordGrid.x = 5`, which changes the copy and
	 * nothing in the client.
	 */
	fun checkNotIntoCopy(holder: KtExpression, field: KaJavaFieldSymbol, member: String, where: PsiElement) {
		if (!annotations.has(field, Annotations.LUA_COPY)) {
			return
		}
		val name = field.name.asString()
		val copy = "val $name = ${holder.text}; $name.$member = ..."
		throw Rejection(where, "${holder.text} hands out a copy of the client's value, so changing $name.$member here"
			+ " changes nothing in the client; " + if (field.isVal) "change a local copy instead: $copy"
			else "change a local copy and assign it back: $copy; ${holder.text} = $name")
	}

	/** A field: of the global table for a static one, `Player.x`, or of the object, `npc.coordGrid`. */
	fun field(where: PsiElement, field: KaJavaFieldSymbol, owner: KaClassSymbol, receiver: Expression?): Expression {
		checkUse(field, owner, where)
		val name = memberName(field, owner)
		if (field.isStatic) {
			val global = global(owner, where)
			if (global.isEmpty()) {
				unit.useGlobal(name)
				return Expression.name(name)
			}
			return member(table(owner, global), name)
		}
		val target = receiver ?: throw IllegalStateException("an API field without a receiver: ${where.text}")
		return member(target, name)
	}

	/** A constant of an API enum, `GameState.GAME`, whose value is the client's own number or string. */
	fun enumConstant(where: PsiElement, constant: KaEnumEntrySymbol, owner: KaClassSymbol): Expression {
		checkUse(constant, owner, where)
		return member(table(owner, global(owner, where)), constant.name.asString())
	}

	/**
	 * A client enum's value as its name, as Kotlin writes it: the value is the client's number, which the table's
	 * `FromID` turns into the name, `GameState.FromID(state)`. A table without `FromID` gives its values no names.
	 */
	fun enumName(where: PsiElement, value: Expression, owner: KaClassSymbol): Expression {
		if (!annotations.has(owner, Annotations.LUA_FROM_ID)) {
			throw Rejection(where, ClientApi.noFromId(owner.classId?.shortClassName?.asString().orEmpty()))
		}
		return ClientApi.fromId(table(owner, global(owner, where)), value)
	}

	/** `ui.Text(parent)` as the table's constructor, `ui.Text.new(parent)`; a `@LuaType` as a table to fill in. */
	fun construct(where: PsiElement, constructor: KaConstructorSymbol, owner: KaClassSymbol,
		arguments: List<Expression>): Expression {
		checkUse(constructor, owner, where)
		val global = annotations.value(owner, Annotations.LUA_GLOBAL) ?: return Expression.Table(listOf())
		return Expression.Call(Expression.field(table(owner, global), "new"), arguments)
	}

	/** The name a member has in the Lua: its `@LuaName`, or its own; a constant table's names lose their prefix. */
	private fun memberName(member: KaCallableSymbol, owner: KaClassSymbol): String {
		annotations.value(member, Annotations.LUA_NAME)?.let { return it }
		val name = member.callableId!!.callableName.asString()
		return if (annotations.has(owner, Annotations.LUA_CONSTANTS) && name.startsWith(LuaConstants.PREFIX)) {
			name.removePrefix(LuaConstants.PREFIX)
		} else {
			name
		}
	}

	/** The Lua path of a class's global table; a `@LuaType` has none, and so no static members. */
	private fun global(owner: KaClassSymbol, where: PsiElement): String =
		annotations.value(owner, Annotations.LUA_GLOBAL)
		?: throw IllegalStateException("a static member of a @LuaType at ${where.text}")

	/**
	 * A table as an expression: `ui.Text` is the field `Text` of the global `ui`, and a module's `zyklib.reactive` the
	 * field `reactive` of the local `zyklib`, which the file requires.
	 */
	private fun table(owner: KaClassSymbol, global: String): Expression = with(unit.session) {
		val segments = global.split(".")
		var outermost = owner
		while (true) {
			outermost = outermost.classId?.outerClassId?.let { findClass(it) } ?: break
		}
		val module = annotations.value(outermost, Annotations.LUA_MODULE)
		val first = if (module != null) {
			unit.required(segments.first(), module)
		} else {
			unit.useGlobal(segments.first())
			Expression.name(segments.first())
		}
		segments.drop(1).fold(first, ::member)
	}

	/** `table.name`, or `table["name"]` for a key that is not a Lua name, such as an enum's. */
	private fun member(table: Expression, name: String): Expression =
		if (LuaNames.isName(name)) Expression.field(table, name) else Expression.Index(table, Expression.string(name))

	/**
	 * Rejects what the build does not allow: a member that needs a permission the manifest does not declare, and a
	 * development-only member outside the dev source set.
	 */
	private fun checkUse(member: KaSymbol, owner: KaClassSymbol, where: PsiElement) {
		val display = display(member, owner)
		val devOnly = annotations.has(member, Annotations.LUA_DEV_ONLY) ||
			annotations.has(owner, Annotations.LUA_DEV_ONLY)
		if (devOnly && !unit.options.plugin.dev()) {
			throw Rejection(where, "$display is for development only and must never ship; move the code that uses it"
				+ " into the dev source set (src/dev/kotlin), whose Lua stays out of the zip.")
		}
		val permission = annotations.value(member, Annotations.LUA_PERMISSION)
			?: annotations.value(owner, Annotations.LUA_PERMISSION)
		if (permission != null && permission !in unit.options.plugin.permissions()) {
			throw Rejection(where, "$display needs the $permission permission, which the manifest does not declare; add"
				+ " it to the build, oneiro { plugin { permissions { optional($permission) } } }, and check"
				+ " ${ClientApi.permissionCheck(permission)} before using it.")
		}
		if (member !is KaJavaFieldSymbol) {
			unit.reviews.check(member, display, where)
		}
	}

	/** An API field being assigned, such as a menu entry's `overrideText`, which moderation may review. */
	fun assigned(field: KaJavaFieldSymbol, owner: KaClassSymbol, where: PsiElement) {
		unit.reviews.check(field, display(field, owner), where)
	}

	/** `Chat.AddMessage`, `ui.Text(...)`, `log`: a member as the developer wrote it. */
	private fun display(member: KaSymbol, owner: KaClassSymbol): String {
		val ownerName = owner.classId?.shortClassName?.asString().orEmpty()
		if (member is KaConstructorSymbol) {
			return "$ownerName(...)"
		}
		val name = (member as? KaCallableSymbol)?.let { memberName(it, owner) } ?: ownerName
		return if (annotations.value(owner, Annotations.LUA_GLOBAL)?.isEmpty() == true) name else "$ownerName.$name"
	}
}
