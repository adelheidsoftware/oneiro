package org.adelheid.oneiro.kotlin

import com.intellij.psi.util.PsiTreeUtil
import org.adelheid.oneiro.lowering.LuaModule
import org.adelheid.oneiro.lowering.PluginLifecycle
import org.adelheid.oneiro.lua.Block
import org.adelheid.oneiro.lua.Comment
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.Statement
import org.adelheid.oneiro.lua.Trivia
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaJavaFieldSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty

/**
 * The plugin class as `main.lua`, a chunk of locals: the file's constants, the class's properties and functions,
 * whatever their visibility, then the statements of `startUp()`, which run when the client loads the file, a
 * subscription for each `@Subscribe` function, and the global `PluginShutdown`, which unsubscribes them and runs
 * `shutDown()`. A property of a settings interface holds nothing: calls through it call the settings module. The dev
 * source set's `DevPlugin` class becomes `dev/main.lua` in the same way.
 */
internal class PluginClass(private val unit: FileLowering, private val type: KtClass) {

	private val functions = Functions(unit)
	private val dev = unit.isDevEntry(type)

	fun lower(header: List<Comment>): LuaModule {
		val members = mutableListOf<Member>()
		for (constant in unit.file.declarations.filterIsInstance<KtProperty>()) {
			members += local(constant)
		}
		val subscriptions = mutableListOf<Subscription>()
		// The class's members stand apart from the file's constants above them
		val fileConstants = members.size
		for (member in type.declarations) {
			try {
				when {
					member is KtProperty -> if (!unit.isSettingsProperty(member)) {
						members += local(member, members.size == fileConstants && fileConstants > 0)
					}
					member is KtNamedFunction && isLifecycle(member) -> {}
					member is KtNamedFunction && unit.hasAnnotation(member, Annotations.SUBSCRIBE) -> {
						val subscription = subscription(member)
						subscriptions += subscription
						members += functions.entryFunction(member, type, subscription.parameters)
					}
					member is KtNamedFunction ->
						members += functions.entryFunction(member, type, member.valueParameters.map {
							unit.names.luaName(it.name!!)
						})
					member is KtObjectDeclaration && member.isCompanion() ->
						member.declarations.filterIsInstance<KtProperty>().forEach { members += local(it) }
					else -> throw Rejection.notYet(member, "This member of the plugin class")
				}
			} catch (rejection: Rejection) {
				unit.reject(rejection)
			}
		}
		members += lifecycle(subscriptions)
		val path = if (dev) "dev/main.lua" else "main.lua"
		return LuaModule.of(path, header, Block(unit.assemble(null, false, members), listOf()))
	}

	/** A `@Subscribe` function: the event it subscribes to, and the parameters of its Lua function. */
	private class Subscription(val function: KtNamedFunction, val event: String, val parameters: List<String>)

	/**
	 * Checks a `@Subscribe` function: one parameter, the payload of the event it subscribes to, and the return type
	 * that event's callbacks have; the build must declare the permission or manifest flag the event needs. A function
	 * whose event passes nothing gets a Lua function without parameters.
	 */
	private fun subscription(function: KtNamedFunction): Subscription = with(unit.session) {
		val parameter = function.valueParameters.singleOrNull() ?: throw Rejection(function, "A @Subscribe function"
			+ " takes one parameter, the payload of the event it subscribes to, such as InventoryUpdatedEventPayload.")
		val payload = parameter.symbol.returnType.expandedSymbol
		val event = payload?.let { unit.annotations.value(it, Annotations.LUA_EVENT) }
		if (payload == null || event == null) {
			throw Rejection(parameter, "${parameter.typeReference?.text} is not an event's payload; the parameter of a"
				+ " @Subscribe function names the event, such as InventoryUpdatedEventPayload for InventoryUpdated.")
		}
		// Only EntityDraw's callbacks return something, a boolean, which the binding gives as returns = boolean.class
		val predicate = unit.annotations.hasArgument(payload, Annotations.LUA_EVENT, "returns")
		val returned = function.symbol.returnType
		if (predicate && !unit.types.isBoolean(returned)) {
			throw Rejection(function, "$event's callbacks return a boolean the client acts on; declare ${function.name}"
				+ " to return Boolean.")
		}
		if (!predicate && !returned.isUnitType) {
			throw Rejection(function, "$event's callbacks return nothing; declare ${function.name} without a return"
				+ " type.")
		}
		checkEventAllowed(payload, event, function)
		unit.reviews.check(payload, "Subscribing to $event", function)
		val passesNothing = payload.declaredMemberScope.callables
			.none { it is KaJavaFieldSymbol || it is KaPropertySymbol }
		if (!passesNothing) {
			return Subscription(function, event, listOf(unit.names.luaName(parameter.name!!)))
		}
		val uses = PsiTreeUtil.collectElementsOfType(function.bodyExpression, KtNameReferenceExpression::class.java)
			.any { it.resolveSymbol()?.psi == parameter }
		if (uses) {
			throw Rejection(parameter, "The $event event passes nothing, so its payload has nothing to read; leave"
				+ " ${parameter.name} unused.")
		}
		return Subscription(function, event, listOf())
	}

	/** An event that needs a permission or a manifest flag fails to compile unless the build declares it. */
	private fun checkEventAllowed(payload: KaClassSymbol, event: String, function: KtNamedFunction) {
		val permission = unit.annotations.value(payload, Annotations.LUA_PERMISSION)
		if (permission != null && permission !in unit.options.plugin.permissions()) {
			throw Rejection(function, "The $event event needs the $permission permission, which the manifest does not"
				+ " declare; add it to the build, oneiro { plugin { permissions { optional($permission) } } }.")
		}
		val flag = unit.annotations.value(payload, Annotations.LUA_MANIFEST_FLAG)
		if (flag != null && flag !in unit.options.plugin.manifestFlags()) {
			val setting = if (flag.startsWith("has")) flag[3].lowercaseChar() + flag.substring(4) else flag
			throw Rejection(function, "The $event event fires only when the manifest sets $flag; set $setting = true in"
				+ " the build's oneiro { plugin { } } block.")
		}
	}

	/**
	 * A property, or a constant of the file or the companion object: a local of `main.lua`, after a blank line when
	 * the source has one before it or `apart` asks for one.
	 */
	private fun local(property: KtProperty, apart: Boolean = false): Member {
		val initializer = property.initializer
			?: throw Rejection.notYet(property, "A property of the plugin class without a value")
		val name = unit.names.luaName(property.name!!)
		val value = BodyLowering(unit, type, false).expression(initializer)
		val trivia = Trivia(apart || unit.source.blankLineBefore(property), unit.source.leading(property) +
			listOfNotNull(property.docComment).flatMap(KDocText::description).map(Comment::line),
			unit.source.trailing(property))
		val origin = unit.source.position(unit.codeStart(property))
		val declared = Block.Entry(Statement.Local(listOf(name), listOf(value)), trivia, origin)
		val assigned = Block.Entry(Statement.assignment(Expression.name(name), value), trivia, origin)
		return Member(listOf(property), false, name, listOf(declared), listOf(assigned), References.of(unit, property))
	}

	/**
	 * What the plugin class adds after its members: the statements of `startUp()`, the subscriptions, and the global
	 * `PluginShutdown`, which unsubscribes them and then runs `shutDown()`.
	 */
	private fun lifecycle(subscriptions: List<Subscription>): List<Member> {
		val members = mutableListOf<Member>()
		val startUp = lifecycleFunction("startUp")
		val shutDown = lifecycleFunction("shutDown")
		val prefix = unit.options.plugin.id() + if (dev) ".dev." else "."
		if (startUp != null) {
			// Start-up code stands apart from the functions above it
			val entries = lifecycleBody(startUp).entries().toMutableList()
			if (entries.isNotEmpty()) {
				val first = entries.first()
				val trivia = Trivia(true, first.trivia().leading(), first.trivia().trailing())
				entries[0] = Block.Entry(first.statement(), trivia, first.origin())
			}
			members += Member(listOf(startUp), false, null, entries, listOf(), References.of(unit, startUp))
		}
		if (subscriptions.isNotEmpty()) {
			unit.useGlobal("Event")
			val entries = subscriptions.mapIndexed { index, subscription ->
				val statement = PluginLifecycle.subscribe(subscription.event, prefix + subscription.function.name,
					unit.names.luaName(subscription.function.name!!))
				Block.Entry(statement, Trivia(index == 0, listOf(), null),
					unit.source.position(unit.codeStart(subscription.function)))
			}
			members += Member(listOf(), false, null, entries, listOf(), subscriptions.map { it.function }.toSet())
		}
		if (subscriptions.isNotEmpty() || shutDown != null || dev) {
			val body = subscriptions.map {
				Block.Entry.of(PluginLifecycle.unsubscribe(it.event, prefix + it.function.name))
			}.toMutableList()
			var closing = listOf<Comment>()
			if (shutDown != null) {
				val lowered = lifecycleBody(shutDown)
				body += lowered.entries()
				closing = lowered.closingComments()
			}
			val origin = shutDown?.let { unit.source.position(unit.codeStart(it)) }
			members += Member(listOfNotNull(shutDown), true, null, PluginLifecycle.shutdown(body, closing, dev, origin),
				listOf(), References.of(unit, shutDown))
		}
		return members
	}

	private fun lifecycleFunction(name: String): KtNamedFunction? =
		type.declarations.filterIsInstance<KtNamedFunction>().firstOrNull { isLifecycle(it) && it.name == name }

	/** `startUp()` and `shutDown()`, which override the plugin base class's. */
	private fun isLifecycle(function: KtNamedFunction): Boolean = function.hasModifier(KtTokens.OVERRIDE_KEYWORD) &&
		(function.name == "startUp" || function.name == "shutDown") && function.valueParameters.isEmpty()

	/** A lifecycle function's statements, with the comments above the function above them. */
	private fun lifecycleBody(function: KtNamedFunction): Block {
		val block = function.bodyBlockExpression
			?: throw Rejection(function, "${function.name}() needs a block body, whose statements become the Lua's.")
		val above = functions.comments(function)
		val lowered = BodyLowering(unit, type, false).block(block)
		if (above.isEmpty() || lowered.entries().isEmpty()) {
			return lowered
		}
		val first = lowered.entries().first()
		val entries = listOf(Block.Entry(first.statement(), Trivia(first.trivia().blankLineBefore(),
			above + first.trivia().leading(), first.trivia().trailing()), first.origin())) + lowered.entries().drop(1)
		return Block(entries, lowered.closingComments())
	}
}
