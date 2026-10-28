package org.adelheid.oneiro.kotlin.library

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.adelheid.oneiro.kotlin.core.Annotations
import org.adelheid.oneiro.kotlin.core.BodyLowering
import org.adelheid.oneiro.kotlin.core.Receiver
import org.adelheid.oneiro.kotlin.core.Rejection
import org.adelheid.oneiro.lowering.translation.Helper
import org.adelheid.oneiro.lowering.translation.IndexArithmetic
import org.adelheid.oneiro.lowering.translation.Inlining
import org.adelheid.oneiro.lowering.translation.LuaPatterns
import org.adelheid.oneiro.lua.BinaryOperator
import org.adelheid.oneiro.lua.Block
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.LuaNames
import org.adelheid.oneiro.lua.NameUses
import org.adelheid.oneiro.lua.Statement
import org.adelheid.oneiro.lua.UnaryOperator
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * The Kotlin library on the Lua tables that hold its collections, as in the Java front end: a list or an array is a
 * sequence that counts from 1, a set a table of its elements as keys, and a map a table of keys to values. Members
 * become Lua's own operations where it has them, `list.size` as `#list`, and helpers where it does not, a map's
 * `size`. A member these do not list is not supported yet.
 */
internal class LibraryCalls(private val body: BodyLowering) {

	private val unit = body.unit
	private val types = body.types
	val strings = StringMembers(body)
	private val copies = CollectionCopies(body)

	/** How Lua holds a collection, which decides what its members become. */
	enum class Kind { SEQUENCE, MAP, SET }

	fun kind(type: KaType?): Kind? = when {
		type == null -> null
		types.isSequence(type) -> Kind.SEQUENCE
		types.isMap(type) -> Kind.MAP
		types.isSet(type) -> Kind.SET
		else -> null
	}

	/**
	 * A property of the library: a collection's `size`, a list's `lastIndex`, and a map's `keys` and `values` as values
	 * of their own, a set and a list of them; the size of either is the map's.
	 */
	fun property(where: KtExpression, property: KaCallableSymbol, receiver: Receiver?): Expression {
		val id = property.callableId
		val name = id?.callableName?.asString()
		val kind = receiver?.let { kind(type(it)) }
		if (receiver != null && body.chars.isChar(type(receiver))) {
			name?.let { body.chars.member(it, receiver) }?.let { return it }
		}
		if (id?.packageName?.asString() == "kotlin.math" && name == "PI") {
			return mathField("pi")
		}
		val text = receiver?.let(::type)?.let(types::isString) == true
		LIMITS[id?.classId?.asFqNameString() + "." + name]?.let { return it(this) }
		if (receiver != null && type(receiver)?.let(types::isPair) == true && name in PAIR_PARTS) {
			return Expression.field(body.expression(receiver), name!!)
		}
		val view = (receiver as? Receiver.Written)?.expression?.let(::mapView)
		if (view != null && name == "size") {
			return helper(Helper.TABLE_LENGTH, body.expression(view.map))
		}
		return when {
			text && name == "length" -> length(body.expression(receiver))
			receiver == null || kind == null -> null
			name == "size" && kind == Kind.SEQUENCE -> length(body.expression(receiver))
			name == "size" -> helper(Helper.TABLE_LENGTH, body.expression(receiver))
			name == "lastIndex" && kind == Kind.SEQUENCE ->
				IndexArithmetic.minusOne(length(body.expression(receiver)))
			// Kotlin's keys keep the map's order, which a set of them in a helper orderedTable() keeps too
			name == "keys" && kind == Kind.MAP ->
				helper(Helper.ADD_KEYS, helper(Helper.ORDERED_TABLE), body.expression(receiver))
			name == "values" && kind == Kind.MAP -> helper(Helper.VALUES_OF, body.expression(receiver))
			else -> null
		} ?: throw Rejection.notYet(where, "Reading ${display(property)}")
	}

	/**
	 * A call of a function of the library: a member or extension of a collection, or a function outside any class,
	 * such as `minOf` or `mutableListOf`.
	 *
	 * @param arguments the arguments, in the order of the parameters they go to
	 */
	fun call(where: KtExpression, function: KaCallableSymbol, receiver: Receiver?, arguments: LibraryArguments):
		Expression {
		val name = function.callableId?.callableName?.asString().orEmpty()
		val receiverType = receiver?.let(::type)
		// split gives its own reason, after the separator it takes
		if (name != "split" && (isRegex(function.callableId?.classId?.asFqNameString()) || name == "toRegex" ||
				arguments.any { isRegex(type(it)) })) {
			throw Rejection(where, LuaPatterns.NO_REGEX)
		}
		if (receiver != null && receiverType != null && body.strings.isBuilder(receiverType)) {
			return builder(where, name, receiver, arguments)
		}
		if (receiver != null && body.chars.isChar(receiverType)) {
			body.chars.member(name, receiver)?.let { return it }
		}
		if (name == "toString" && receiver != null && arguments.isEmpty()) {
			return body.strings.toText(written(receiver, where, "toString()"))
		}
		if (name == "format" && function.callableId?.packageName?.asString() == "kotlin.text") {
			// "%d".format(n), or String.format("%d", n), whose receiver is the String class
			return if (receiver != null && receiverType != null && types.isString(receiverType)) {
				body.strings.format(where, written(receiver, where, "format(...)"), arguments)
			} else {
				body.strings.format(where, arguments.first(), arguments.drop(1))
			}
		}
		// A text's count { ... } and its kin walk its chars in a loop, as a chain of collection functions does
		if (receiverType != null && types.isString(receiverType)) {
			body.hoistedChain(where)?.let { return it }
		}
		val lowered = when (kind(receiverType)) {
			Kind.SEQUENCE -> sequence(where, name, receiver!!, arguments)
			Kind.MAP -> map(where, name, receiver!!, arguments)
			Kind.SET -> set(where, name, receiver!!, arguments)
			null -> when {
				receiverType != null && types.isString(receiverType) ->
					strings.member(where, name, receiver, arguments)
				isKotlinError(function) -> Expression.call(Expression.name("error"), body.expression(arguments[0]))
				isKotlinIo(function, "println") -> printed(arguments)
				// String(chars), a char array's chars as text
				name == "String" && function.callableId?.packageName?.asString() == "kotlin.text" &&
					arguments.size == 1 -> tableCall("concat", body.expression(arguments[0]))
				function.callableId?.classId?.asFqNameString() == "java.lang.Math" -> javaMath(name, arguments)
				receiverType != null && isNumber(receiverType) -> number(name, receiver, receiverType, arguments)
				isMath(function) -> math(name, arguments)
				else -> creation(where, name, arguments)
			}
		}
		if (lowered == null && function.callableId?.packageName?.asString() == "kotlin.collections" &&
			name in CollectionLoops.FUNCTIONS) {
			return body.hoistedChain(where) ?: throw Rejection(where, CHAIN_IN_STATEMENT)
		}
		return lowered ?: throw Rejection.notYet(where, "Calling ${display(function)}")
	}

	/**
	 * A call that changes its collection, made as a statement: `list.add(x)` as `list[#list + 1] = x`, `map.put(k, v)`
	 * as `map[k] = v`, `set.remove(x)` as `set[x] = nil`; null when the call is none of these.
	 */
	fun statement(where: KtExpression, function: KaCallableSymbol, receiver: Receiver,
		arguments: LibraryArguments): List<Statement>? {
		val name = function.callableId?.callableName?.asString()
		val kind = kind(type(receiver))
		if (kind != null) {
			loopOver(where, name, kind, receiver, arguments)?.let { return it }
		}
		if (kind != null && name == "clear" && arguments.isEmpty()) {
			return listOf(Statement.CallStatement(helper(Helper.CLEAR, body.expression(receiver))))
		}
		val statement = when (kind) {
			Kind.SEQUENCE -> when {
				(name == "add" || name == "addLast") && arguments.size == 1 -> appended(receiver, arguments[0])
				name == "addFirst" -> Statement.CallStatement(tableCall("insert", body.expression(receiver),
					Expression.integer(1), element(arguments[0])))
				// A sort in natural order, which the elements' < gives, and an object's __lt
				name == "sort" && arguments.isEmpty() -> Statement.CallStatement(tableCall("sort",
					body.expression(receiver)))
				name == "sortWith" -> Statement.CallStatement(tableCall("sort", body.expression(receiver),
					body.comparators.lessThan(arguments[0])))
				name == "sortBy" || name == "sortByDescending" -> Statement.CallStatement(tableCall("sort",
					body.expression(receiver), body.comparators.byKey(arguments[0], name == "sortByDescending")))
				name == "sortDescending" && arguments.isEmpty() -> Statement.CallStatement(tableCall("sort",
					body.expression(receiver), body.comparators.reversed(where)))
				name == "addAll" && arguments.size == 1 -> copies.addedAll(receiver, Kind.SEQUENCE, arguments[0])
				name == "add" && arguments.size == 2 -> Statement.CallStatement(tableCall("insert",
					body.expression(receiver), position(arguments[0]), element(arguments[1])))
				name == "set" -> Statement.assignment(Expression.Index(body.expression(receiver),
					position(arguments[0])), element(arguments[1]))
				else -> null
			}
			Kind.MAP -> when (name) {
				"put", "set" -> {
					checkKey(arguments[0])
					Statement.assignment(entry(receiver, arguments[0]), element(arguments[1]))
				}
				"remove" -> {
					checkKey(arguments[0])
					Statement.assignment(entry(receiver, arguments[0]), Expression.Nil())
				}
				"getOrPut", "putIfAbsent" -> storedIfAbsent(name, receiver, arguments)
				"putAll" -> copies.addedAll(receiver, Kind.MAP, arguments[0])
				else -> null
			}
			Kind.SET -> when (name) {
				"add" -> {
					checkKey(arguments[0])
					Statement.assignment(entry(receiver, arguments[0]), Expression.BooleanLiteral(true))
				}
				"remove" -> {
					checkKey(arguments[0])
					Statement.assignment(entry(receiver, arguments[0]), Expression.Nil())
				}
				"addAll" -> copies.addedAll(receiver, Kind.SET, arguments[0])
				else -> null
			}
			null -> null
		}
		return statement?.let(::listOf)
	}

	/**
	 * `list.add(x)` as `list[#list + 1] = x`, or `table.insert(list, x)` for a list from a call, which Lua must not
	 * make twice.
	 */
	private fun appended(receiver: Receiver, value: KtExpression): Statement {
		if (receiver is Receiver.Written && isCall(receiver.expression)) {
			return Statement.CallStatement(tableCall("insert", body.expression(receiver), element(value)))
		}
		val list = body.expression(receiver)
		return Statement.assignment(Expression.Index(list, nextIndex(list)), element(value))
	}

	/**
	 * `map.getOrPut(key) { value }` as a statement, and `map.putIfAbsent(key, value)`:
	 * `if map[key] == nil then map[key] = value end`.
	 */
	private fun storedIfAbsent(name: String, receiver: Receiver, arguments: LibraryArguments): Statement {
		checkKey(arguments[0])
		val default = if (name == "getOrPut") {
			val lambda = arguments[1] as? KtLambdaExpression
			lambda?.let { body.lambdas.inline(it, "getOrPut") }?.value
				?: return Statement.CallStatement(getOrPut(receiver, arguments))
		} else {
			null
		}
		// The map and the key are read before the value, as Kotlin reads them
		val entry = Expression.Index(body.reused(receiver, READ_TWICE), body.reused(arguments[0], READ_TWICE))
		val value = if (default != null) body.expression(default) else element(arguments[1])
		return Statement.ifThen(Expression.binary(entry, BinaryOperator.EQUAL, Expression.Nil()),
			Block.of(Statement.assignment(entry, value)))
	}

	/**
	 * `forEach` and `removeAll` with a lambda, as the loops they make: the lambda's code is the loop's body, and its
	 * parameters the loop's variables. A list is walked with `ipairs`, and backwards to remove from it; a set or a map
	 * with `pairs`.
	 */
	private fun loopOver(where: KtExpression, name: String?, kind: Kind, receiver: Receiver,
		arguments: LibraryArguments): List<Statement>? {
		val argument = arguments.singleOrNull() ?: return null
		val lambda = argument as? KtLambdaExpression ?: return if (name == "forEach") {
			listOf(calledFor(kind, receiver, argument))
		} else {
			null
		}
		return when (name) {
			"forEach" -> {
				val entry = if (kind == Kind.MAP) entryNames(lambda) else null
				val inlined = body.lambdas.inline(lambda, name)
				val walk = if (kind == Kind.SEQUENCE) "ipairs" else "pairs"
				val names = entry ?: if (kind == Kind.SEQUENCE) listOf("_") + inlined.parameters else inlined.parameters
				val walked = Expression.call(Expression.name(walk), body.expression(receiver))
				val code = inlined.code
				val variables = if (kind == Kind.MAP) entryVariables(names[0], names[1], code) else names
				listOf(Statement.GenericFor(variables, listOf(walked), code))
			}
			"removeAll", "removeIf" -> listOf(removed(where, name, kind, receiver, lambda))
			"forEachIndexed" -> if (kind == Kind.SEQUENCE) listOf(indexedFor(lambda, receiver)) else null
			else -> null
		}
	}

	/** `list.forEachIndexed { index, name -> ... }`: the loop over the list's positions and elements. */
	private fun indexedFor(lambda: KtLambdaExpression, receiver: Receiver): Statement {
		val parameters = lambda.functionLiteral.valueParameters
		if (parameters.size != 2 || parameters.any { it.destructuringDeclaration != null }) {
			throw Rejection.notYet(lambda, "forEachIndexed with a lambda that destructures its parameters")
		}
		val inlined = body.lambdas.inline(lambda, "forEachIndexed")
		return body.loops.indexedLoop(parameters[0].takeIf { it.name != "_" }, inlined.parameters[1],
			body.expression(receiver), inlined.code)
	}

	/**
	 * For `forEach { entry -> ... }` over a map, the loop's key and value, which `entry.key` and `entry.value` read, as
	 * Lua has no entry of its own; null for a lambda that destructures the entry, or takes the key and the value.
	 */
	private fun entryNames(lambda: KtLambdaExpression): List<String>? {
		val literal = lambda.functionLiteral
		val parameter = literal.valueParameters.singleOrNull()
		if (parameter?.destructuringDeclaration != null || literal.valueParameters.size > 1) {
			return null
		}
		val key = body.invent("key", lambda)
		val value = body.invent("value", lambda)
		unit.aliasEntry(parameter ?: literal, key, value)
		return listOf(key, value)
	}

	/**
	 * `forEach(f)` with a function rather than a lambda: a loop that calls it with each element, or with each key and
	 * value of a map.
	 */
	private fun calledFor(kind: Kind, receiver: Receiver, function: KtExpression): Statement {
		val values = when (kind) {
			Kind.MAP -> listOf(body.invent("key", function), body.invent("value", function))
			else -> listOf(body.invent("item", function))
		}
		val names = if (kind == Kind.SEQUENCE) listOf("_") + values else values
		val walk = if (kind == Kind.SEQUENCE) "ipairs" else "pairs"
		// A reference's small function is opened into the call it makes
		val lowered = body.expression(function)
		val arguments = values.map(Expression::name)
		val call = Inlining.statement(lowered, arguments)
			?: Statement.CallStatement(Expression.Call(lowered, arguments))
		return Statement.GenericFor(names, listOf(Expression.call(Expression.name(walk), body.expression(receiver))),
			Block.of(call))
	}

	/**
	 * `list.removeAll { ... }` as a loop from the end, which removing does not disturb; a set's as a loop of `pairs`.
	 */
	private fun removed(where: KtExpression, name: String, kind: Kind, receiver: Receiver,
		lambda: KtLambdaExpression): Statement {
		val inlined = body.lambdas.inline(lambda, name)
		val test = inlined.value?.let(body::expression)
			?: throw Rejection.notYet(lambda, "A $name whose lambda does more than test each element")
		val item = inlined.parameters.singleOrNull() ?: throw Rejection.notYet(lambda, "This $name")
		val table = body.reused(receiver, READ_TWICE)
		return when (kind) {
			Kind.SEQUENCE -> {
				val index = body.invent("i", where)
				val element = Statement.Local(listOf(item), listOf(Expression.Index(table, Expression.name(index))))
				val removal = Statement.CallStatement(tableCall("remove", table, Expression.name(index)))
				Statement.NumericFor(index, length(table), Expression.integer(1), Expression.integer(-1),
					Block.of(element, Statement.ifThen(test, Block.of(removal))))
			}
			Kind.SET -> {
				val removal = Statement.assignment(Expression.Index(table, Expression.name(item)), Expression.Nil())
				Statement.GenericFor(listOf(item), listOf(Expression.call(Expression.name("pairs"), table)),
					Block.of(Statement.ifThen(test, Block.of(removal))))
			}
			Kind.MAP -> throw Rejection.notYet(where, "$name on a map")
		}
	}

	/**
	 * `list[i]` and `map[key]`, read or assigned: a position Kotlin counts from 0 is Lua's plus one, unless it names a
	 * loop variable that already counts from 1, and a key stays as it is.
	 */
	fun index(where: KtExpression, receiver: Receiver, key: KtExpression): Expression =
		when (kind(type(receiver))) {
			Kind.SEQUENCE -> Expression.Index(body.expression(receiver), position(key))
			Kind.MAP -> {
				checkKey(key)
				entry(receiver, key)
			}
			else -> if (type(receiver)?.let(types::isString) == true) {
				characterAt(body.expression(receiver), key)
			} else {
				throw Rejection.notYet(where, "Indexing this type")
			}
		}

	/**
	 * The char at a position of a text, `text[i]`, as a one-character string: `text:sub(i + 1, i + 1)`. The position
	 * appears twice; see [BodyLowering.reused].
	 */
	fun characterAt(text: Expression, position: KtExpression): Expression {
		val index = body.reused(position(position), position, "Lua would evaluate this position twice", "position")
		return Expression.methodCall(text, "sub", index, index)
	}

	/**
	 * `x in collection`: a list's `contains`, or whether a set or map has the key.
	 *
	 * @param element the expression whose type the value has
	 */
	fun contains(where: PsiElement, element: KtExpression, value: Expression, collection: Receiver): Expression {
		if (type(collection)?.let(types::isString) == true) {
			return strings.contains(body.expression(collection), value)
		}
		val view = (collection as? Receiver.Written)?.expression?.let(::mapView)
		if (view != null && view.selector == "keys") {
			checkKey(element)
			return present(Expression.Index(body.expression(view.map), value))
		}
		return when (kind(type(collection))) {
			Kind.SEQUENCE -> helper(Helper.LIST_CONTAINS, body.expression(collection), value)
			Kind.MAP, Kind.SET -> {
				checkKey(element)
				present(Expression.Index(body.expression(collection), value))
			}
			null -> throw Rejection.notYet(where, "in on this type")
		}
	}

	/**
	 * A constructor of the library: an empty list, map or set, or an array of zeros or `false`. A `HashMap` or
	 * `HashSet` is a plain table, whose order Kotlin leaves open too; a `LinkedHashMap` or `LinkedHashSet` keeps
	 * the order its keys were added, through a helper.
	 */
	fun construct(where: KtExpression, type: ClassId, arguments: LibraryArguments): Expression {
		val name = type.asFqNameString()
		if (isRegex(name)) {
			throw Rejection(where, LuaPatterns.NO_REGEX)
		}
		if (name in PAIRS) {
			return pair(arguments)
		}
		if (name == "kotlin.Array") {
			return grid(where, arguments)
		}
		if (name in PRIMITIVE_ARRAYS) {
			val size = arguments.singleOrNull() ?: throw Rejection.notYet(where, "An array with an initializer")
			return helper(Helper.FILLED, body.expression(size), PRIMITIVE_ARRAYS.getValue(name))
		}
		if (name == "java.lang.StringBuilder") {
			return body.strings.newBuilder(arguments)
		}
		val source = arguments.singleOrNull()
		val sourceType = source?.let(::type)
		// A capacity, which a Lua table needs no word about
		if (sourceType != null && types.isIntegral(sourceType)) {
			return construct(where, type, LibraryArguments(arguments.where, listOf(), mapOf()))
		}
		val comparator = sourceType?.let { with(unit.session) { it.expandedSymbol?.classId?.asFqNameString() } } ==
			"java.util.Comparator"
		if (arguments.size > 1) {
			throw Rejection.notYet(where, "Creating a ${type.shortClassName} from arguments")
		}
		val empty = when (name) {
			"java.util.ArrayList", "java.util.HashMap", "java.util.HashSet" -> Expression.Table(listOf())
			"java.util.LinkedHashMap", "java.util.LinkedHashSet" -> helper(Helper.ORDERED_TABLE)
			// A sorted map or set orders its keys when pairs walks them, by its comparator or by <
			"java.util.TreeMap", "java.util.TreeSet" ->
				if (comparator) helper(Helper.SORTED_TABLE, body.comparators.lessThan(source))
				else helper(Helper.SORTED_TABLE)
			else -> throw Rejection.notYet(where, "Creating a ${type.shortClassName}")
		}
		if (source == null || comparator) {
			return empty
		}
		// A copy of another collection
		return when {
			name == "java.util.ArrayList" -> copies.listCopy(where, source)
			name.endsWith("Set") -> copies.filled(where, empty, Kind.SET, source)
			else -> copies.filled(where, empty, Kind.MAP, source)
		}
	}

	/** A `Pair` or a `Triple` made of its parts: a table of them, `{ first = a, second = b }`. */
	fun pair(parts: List<KtExpression>): Expression = Expression.Table(parts.mapIndexed { index, part ->
		Expression.TableField.Named(PAIR_PARTS[index], body.expression(part))
	})

	/**
	 * The parts of a pair made in place, `a to b`, `Pair(a, b)` or `Triple(a, b, c)`, in order; null for any other
	 * value.
	 */
	fun pairParts(value: KtExpression): List<KtExpression>? = with(unit.session) {
		val plain = KtPsiUtil.safeDeparenthesize(value)
		if (plain is KtBinaryExpression) {
			val id = plain.operationReference.resolveToCall()?.successfulFunctionCallOrNull()?.symbol?.callableId
			val isTo = id?.packageName?.asString() == "kotlin" && id.classId == null &&
				id.callableName.asString() == "to"
			return if (isTo) listOf(plain.left ?: return null, plain.right ?: return null) else null
		}
		val call = plain as? KtCallExpression ?: return null
		val resolved = call.resolveToCall()?.successfulFunctionCallOrNull() ?: return null
		val constructor = resolved.symbol as? KaConstructorSymbol ?: return null
		if (constructor.containingClassId?.asFqNameString() !in PAIRS) {
			return null
		}
		body.libraryArguments(resolved, call)
	}

	/**
	 * The library's functions that make a collection: `listOf(a, b)` is `{ a, b }`. A map or set keeps the order its
	 * keys were added, as Kotlin promises, through a helper, which a map's entries and a set's elements are added to
	 * in order; `hashMapOf()` and `hashSetOf()` are plain tables, `{ a = 1 }` and `{ a = true }`.
	 */
	private fun creation(where: KtExpression, name: String, arguments: List<KtExpression>): Expression? = when (name) {
		"listOf", "mutableListOf", "arrayListOf", "arrayOf", "intArrayOf", "longArrayOf", "doubleArrayOf",
		"booleanArrayOf", "emptyList", "emptyArray" -> Expression.Table(arguments.map {
			Expression.TableField.Positional(if (isSpread(it)) body.argument(it) else element(it))
		})
		"emptyMap", "emptySet" -> Expression.Table(listOf())
		"hashMapOf" -> Expression.Table(arguments.map { argument ->
			val (key, value) = entryOf(argument)
			entry(key, element(value))
		})
		"hashSetOf" -> Expression.Table(arguments.map { entry(it, Expression.BooleanLiteral(true)) })
		// A read-only map or set with nothing in it has no order to keep
		"mapOf", "setOf" -> if (arguments.isEmpty()) Expression.Table(listOf()) else ordered(name, arguments)
		"mutableMapOf", "linkedMapOf", "mutableSetOf", "linkedSetOf" ->
			if (arguments.isEmpty()) helper(Helper.ORDERED_TABLE) else ordered(name, arguments)
		"sortedMapOf", "sortedSetOf" -> empty(where, name, arguments, helper(Helper.SORTED_TABLE))
		"arrayOfNulls" -> throw Rejection(where, "A new array from arrayOfNulls is full of null, and Lua arrays cannot"
			+ " hold nil; use a list and add elements.")
		"synchronized" -> throw Rejection(where, "Plugins run on one thread, and Lua has no threads to synchronise;"
			+ " run the block's code without synchronized.")
		"assert" -> throw Rejection(where, "Kotlin's assert runs only where the JVM enables assertions, but Lua's"
			+ " assert always runs; write check(condition) { \"...\" } instead.")
		else -> null
	}

	/**
	 * A map or set that keeps its keys' order, filled in that order: a map from a list of its keys and values in turn,
	 * `putEntries(orderedTable(), { "a", 1, "b", 2 })`, and a set from a list of its elements.
	 */
	private fun ordered(name: String, arguments: List<KtExpression>): Expression {
		if (name.lowercase().contains("set")) {
			arguments.forEach(::checkKey)
			return helper(Helper.ADD_ELEMENTS, helper(Helper.ORDERED_TABLE), Expression.Table(arguments.map {
				Expression.TableField.Positional(element(it))
			}))
		}
		val list = arguments.flatMap { argument ->
			val (key, value) = entryOf(argument)
			checkKey(key)
			listOf(Expression.TableField.Positional(element(key)), Expression.TableField.Positional(element(value)))
		}
		return helper(Helper.PUT_ENTRIES, helper(Helper.ORDERED_TABLE), Expression.Table(list))
	}

	/** The key and value of an entry, `key to value`. */
	private fun entryOf(argument: KtExpression): Pair<KtExpression, KtExpression> {
		val (key, value) = pairParts(argument)?.takeIf { it.size == 2 }
			?: throw Rejection.notYet(argument, "An entry not written as key to value")
		return key to value
	}

	/** `name = value` for a key that is a string literal and a Lua name, otherwise `[key] = value`. */
	private fun entry(key: KtExpression, value: Expression): Expression.TableField {
		checkKey(key)
		val literal = (key as? KtStringTemplateExpression)?.takeIf { !it.hasInterpolation() }?.entries?.singleOrNull()
		val text = (literal as? KtLiteralStringTemplateEntry)?.text
		if (text != null && LuaNames.isName(text)) {
			return Expression.TableField.Named(text, value)
		}
		return Expression.TableField.Keyed(body.expression(key), value)
	}

	private fun empty(where: KtExpression, name: String, arguments: List<KtExpression>, table: Expression): Expression {
		if (arguments.isNotEmpty()) {
			throw Rejection.notYet(where, "$name with entries")
		}
		return table
	}

	/** The members of a list or an array; null for one a chain of collection functions takes instead. */
	private fun sequence(where: KtExpression, name: String, receiver: Receiver, arguments: LibraryArguments):
		Expression? {
		// Lowered only for a member this takes: a receiver that is a chain runs its loop before the statement, which
		// must not happen for a call that a chain then takes, with the receiver in it
		val list by lazy { body.expression(receiver) }
		return when (name) {
			"get" -> Expression.Index(list, position(arguments[0]))
			"getOrNull" -> Expression.Index(list, position(arguments[0]))
			// An empty list's first and last are nil, which is firstOrNull's and lastOrNull's null
			"first", "firstOrNull" -> if (arguments.isEmpty()) Expression.Index(list, Expression.integer(1)) else null
			"last", "lastOrNull" -> if (arguments.isEmpty()) {
				val named = body.reused(receiver, READ_TWICE)
				Expression.Index(named, length(named))
			} else {
				null
			}
			"isEmpty", "none" -> if (arguments.isEmpty()) {
				Expression.binary(length(list), BinaryOperator.EQUAL, Expression.integer(0))
			} else {
				null
			}
			"isNotEmpty", "any" -> if (arguments.isEmpty()) {
				Expression.binary(length(list), BinaryOperator.GREATER, Expression.integer(0))
			} else {
				null
			}
			"count" -> if (arguments.isEmpty()) length(list) else null
			"contains" -> helper(Helper.LIST_CONTAINS, list, body.expression(arguments[0]))
			"indexOf" -> helper(Helper.LIST_INDEX_OF, list, body.expression(arguments[0]))
			"removeAt" -> tableCall("remove", list, position(arguments[0]))
			"removeFirst", "removeFirstOrNull" -> tableCall("remove", list, Expression.integer(1))
			"removeLast" -> tableCall("remove", list)
			"joinToString" -> joined(where, receiver, arguments)
			// A copy: Lua's table.move of every element into a new table
			"toMutableList", "toList" -> if (arguments.isEmpty()) {
				val copied = body.reused(receiver, READ_TWICE)
				tableCall("move", copied, Expression.integer(1), length(copied), Expression.integer(1),
					Expression.Table(listOf()))
			} else {
				null
			}
			"concatToString" -> tableCall("concat", list)
			"reversed" -> if (arguments.isEmpty()) helper(Helper.REVERSED, list) else null
			"orEmpty" -> Expression.binary(list, BinaryOperator.OR, Expression.Table(listOf()))
			"isNullOrEmpty" -> {
				val named = body.reused(receiver, READ_TWICE)
				Expression.binary(Expression.binary(named, BinaryOperator.EQUAL, Expression.Nil()), BinaryOperator.OR,
					Expression.binary(length(named), BinaryOperator.EQUAL, Expression.integer(0)))
			}
			"add", "set" -> throw Rejection(where, STATEMENT_ONLY.format(name))
			else -> null
		}
	}

	/**
	 * `list.joinToString(separator)` as `table.concat(list, separator)`, which joins strings and numbers as Kotlin
	 * writes them; its separator is `", "` unless the call gives another. Null for a join with more, which a chain's
	 * loop makes.
	 */
	private fun joined(where: KtExpression, receiver: Receiver, arguments: LibraryArguments): Expression? {
		if (arguments.byName.keys.any { it != "separator" }) {
			return null
		}
		if (type(receiver)?.let(types::elementClass) !in JOINABLE) {
			throw Rejection.notYet(where, "Joining values other than strings and numbers")
		}
		val list = body.expression(receiver)
		val separator = arguments.byName["separator"]?.let(body::expression) ?: Expression.string(", ")
		return tableCall("concat", list, separator)
	}

	/**
	 * `Array(rows) { IntArray(columns) }`, an array of arrays filled as a new array is, as one call of a helper:
	 * `grid(0, rows, columns)`. Each level's initializer must make the same array whatever its index.
	 */
	private fun grid(where: KtExpression, outermost: LibraryArguments): Expression {
		val sizes = mutableListOf<Expression>()
		var arguments: List<KtExpression> = outermost
		while (true) {
			val initializer = arguments.getOrNull(1) as? KtLambdaExpression
			val inner = initializer?.bodyExpression?.statements?.singleOrNull() as? KtCallExpression
			if (arguments.size != 2 || inner == null || usesIndex(initializer)) {
				throw Rejection.notYet(where, "An array whose initializer makes each element differently")
			}
			sizes += body.expression(arguments[0])
			val resolved = with(unit.session) { inner.resolveToCall()?.successfulFunctionCallOrNull() }
			val constructor = resolved?.symbol as? KaConstructorSymbol
			val made = constructor?.containingClassId?.asFqNameString()
			arguments = resolved?.let { body.argumentTrees(it, inner) } ?: listOf()
			if (made in PRIMITIVE_ARRAYS && arguments.size == 1) {
				sizes += body.expression(arguments[0])
				return helper(Helper.GRID, PRIMITIVE_ARRAYS.getValue(made!!), *sizes.toTypedArray())
			}
			if (made != "kotlin.Array") {
				throw Rejection.notYet(where, "An array whose initializer makes each element differently")
			}
		}
	}

	/** Whether an initializer reads the index it is given, `it`, or a parameter it names. */
	private fun usesIndex(initializer: KtLambdaExpression): Boolean = with(unit.session) {
		if (initializer.valueParameters.isNotEmpty()) {
			return true
		}
		PsiTreeUtil.collectElementsOfType(initializer, KtNameReferenceExpression::class.java).any { reference ->
			val symbol = reference.resolveSymbol() as? KaValueParameterSymbol
			symbol != null && symbol.psi == null && reference.getReferencedName() == "it"
		}
	}

	/** The members of a map. */
	private fun map(where: KtExpression, name: String, receiver: Receiver, arguments: List<KtExpression>):
		Expression? = when (name) {
		"firstKey" -> helper(Helper.FIRST_KEY, body.expression(receiver))
		"lastKey" -> helper(Helper.LAST_KEY, body.expression(receiver))
		// A key getValue finds, as it must, reads as any other; a missing one is nil, where Kotlin throws
		"get", "getValue" -> index(where, receiver, arguments[0])
		"getOrDefault" -> {
			checkKey(arguments[0])
			orDefault(entry(receiver, arguments[0]), arguments[1])
		}
		"getOrPut" -> getOrPut(receiver, arguments)
		"getOrElse" -> {
			checkKey(arguments[0])
			orDefault(entry(receiver, arguments[0]), defaultValue(arguments[1], "getOrElse"))
		}
		"orEmpty" -> Expression.binary(body.expression(receiver), BinaryOperator.OR, Expression.Table(listOf()))
		"isNullOrEmpty" -> nullOrEmpty(receiver)
		"containsKey" -> {
			checkKey(arguments[0])
			present(entry(receiver, arguments[0]))
		}
		"isEmpty", "none" -> if (arguments.isEmpty()) empty(body.expression(receiver)) else null
		"isNotEmpty", "any" -> if (arguments.isEmpty()) notEmpty(body.expression(receiver)) else null
		"count" -> if (arguments.isEmpty()) helper(Helper.TABLE_LENGTH, body.expression(receiver)) else null
		"put", "set", "remove" -> throw Rejection(where, STATEMENT_ONLY.format(name))
		else -> null
	}

	/** The members of a set. */
	private fun set(where: KtExpression, name: String, receiver: Receiver, arguments: List<KtExpression>):
		Expression? = when (name) {
		"contains" -> contains(where, arguments[0], body.expression(arguments[0]), receiver)
		// The first and last keys pairs gives, which a sorted set's are
		"first" -> if (arguments.isEmpty()) helper(Helper.FIRST_KEY, body.expression(receiver)) else null
		"last" -> if (arguments.isEmpty()) helper(Helper.LAST_KEY, body.expression(receiver)) else null
		"isEmpty", "none" -> if (arguments.isEmpty()) empty(body.expression(receiver)) else null
		"isNotEmpty", "any" -> if (arguments.isEmpty()) notEmpty(body.expression(receiver)) else null
		"count" -> if (arguments.isEmpty()) helper(Helper.TABLE_LENGTH, body.expression(receiver)) else null
		"orEmpty" -> Expression.binary(body.expression(receiver), BinaryOperator.OR, Expression.Table(listOf()))
		"isNullOrEmpty" -> nullOrEmpty(receiver)
		"add", "remove" -> throw Rejection(where, STATEMENT_ONLY.format(name))
		else -> null
	}

	/** A nullable map's or set's `isNullOrEmpty()`: nil, or a table whose `next` finds no first key. */
	private fun nullOrEmpty(receiver: Receiver): Expression {
		val named = body.reused(receiver, READ_TWICE)
		return Expression.binary(Expression.binary(named, BinaryOperator.EQUAL, Expression.Nil()), BinaryOperator.OR,
			empty(named))
	}

	/**
	 * The value of a lambda that gives a default, `getOrElse(key) { 0 }`, which Lua works out only where the default is
	 * needed, as Kotlin calls the lambda only then.
	 */
	private fun defaultValue(lambda: KtExpression, function: String): KtExpression {
		val literal = KtPsiUtil.safeDeparenthesize(lambda) as? KtLambdaExpression
			?: throw Rejection.notYet(lambda, "$function with a function reference")
		return body.lambdas.value(literal, "$function's default becomes a value of its own; write it as one"
			+ " expression, { default }.")
	}

	/**
	 * `a union b`, `a intersect b` and `a subtract b` of two sets: a new set, which keeps the order the first set's
	 * elements come in, and then the second's, as Kotlin's does, in a helper `orderedTable()`.
	 */
	fun setOperation(where: KtExpression, name: String, left: KtExpression, right: KtExpression): Expression {
		if (kind(type(left)) != Kind.SET || kind(type(right)) != Kind.SET) {
			throw Rejection.notYet(where, "$name of collections that are not sets")
		}
		val first = body.expression(left)
		val second = body.expression(right)
		val result = helper(Helper.ORDERED_TABLE)
		return when (name) {
			"union" -> helper(Helper.ADD_KEYS, helper(Helper.ADD_KEYS, result, first), second)
			"intersect" -> helper(Helper.INTERSECTION, result, first, second)
			else -> helper(Helper.DIFFERENCE, result, first, second)
		}
	}

	/** `map.getOrPut(key) { value }`: the stored value, or the computed one stored first, through a helper. */
	private fun getOrPut(receiver: Receiver, arguments: List<KtExpression>): Expression {
		checkKey(arguments[0])
		return helper(Helper.COMPUTE_IF_ABSENT, body.expression(receiver), body.expression(arguments[0]),
			body.expression(arguments[1]))
	}

	/**
	 * A stored value or a default: `stored or default`, unless the value can be `false`, which `or` would replace too;
	 * then a helper that replaces only nil.
	 */
	private fun orDefault(stored: Expression, default: KtExpression): Expression {
		val type = type(default)
		if (type != null && types.isBoolean(type)) {
			return helper(Helper.VALUE_OR, stored, body.expression(default))
		}
		return Expression.binary(stored, BinaryOperator.OR, body.expression(default))
	}

	/** `kotlin.math`'s functions and Kotlin's `minOf` and `maxOf`, which Lua's `math` library has too. */
	private fun isMath(function: KaCallableSymbol): Boolean {
		val id = function.callableId ?: return false
		val name = id.callableName.asString()
		return when (id.packageName.asString()) {
			"kotlin.comparisons" -> name == "minOf" || name == "maxOf"
			"kotlin.math" -> name in MATH
			else -> false
		}
	}

	private fun isNumber(type: KaType): Boolean = types.isIntegral(type) || types.isFloating(type)

	/**
	 * `println(value)` as the client's `print` of the value as text: a string or an integer as it is, a decimal as
	 * Kotlin writes it, through `formatStr`, and a boolean or null through `tostring`.
	 */
	private fun printed(arguments: LibraryArguments): Expression {
		unit.useGlobal("print")
		val value = arguments.singleOrNull() ?: return Expression.call(Expression.name("print"), Expression.string(""))
		return Expression.call(Expression.name("print"), body.strings.asText(value))
	}

	/** A `StringBuilder`'s members: its pieces joined, and the `append`s a statement makes. */
	private fun builder(where: KtExpression, name: String, receiver: Receiver, arguments: LibraryArguments):
		Expression = when (name) {
		"toString" -> tableCall("concat", body.expression(receiver))
		"append" -> throw Rejection(where, STATEMENT_ONLY.format(name))
		else -> throw Rejection.notYet(where, "StringBuilder.$name")
	}

	private fun isKotlinIo(function: KaCallableSymbol, name: String): Boolean {
		val id = function.callableId ?: return false
		return id.classId == null && id.packageName.asString() == "kotlin.io" && id.callableName.asString() == name
	}

	/** Kotlin's `error(message)`, which throws with the message, as Lua's `error` raises it. */
	private fun isKotlinError(function: KaCallableSymbol): Boolean {
		val id = function.callableId ?: return false
		return id.classId == null && id.packageName.asString() == "kotlin" && id.callableName.asString() == "error"
	}

	/**
	 * A number's conversions and operations: `toInt()` of a floating number drops its fraction, through a helper, while
	 * the other conversions keep the Lua number as it is; `floorDiv` and `mod` round down, as Lua's `//` and `%` do.
	 */
	private fun number(name: String, receiver: Receiver, type: KaType, arguments: List<KtExpression>): Expression? {
		val value = body.expression(receiver)
		return when (name) {
			"toInt", "toLong", "toShort", "toByte" -> if (types.isFloating(type)) {
				helper(Helper.TO_INTEGER, value)
			} else {
				value
			}
			"toDouble", "toFloat" -> value
			"toChar" -> body.chars.character(value)
			"floorDiv" -> Expression.binary(value, BinaryOperator.FLOOR_DIVIDE, body.expression(arguments[0]))
			"mod" -> Expression.binary(value, BinaryOperator.MODULO, body.expression(arguments[0]))
			"inv" -> Expression.Unary(UnaryOperator.BIT_NOT, value)
			// The value, kept within the bounds
			"coerceIn" -> if (arguments.size == 2) {
				mathCall("min", mathCall("max", value, body.expression(arguments[0])), body.expression(arguments[1]))
			} else {
				null
			}
			"coerceAtLeast" -> mathCall("max", value, body.expression(arguments[0]))
			"coerceAtMost" -> mathCall("min", value, body.expression(arguments[0]))
			// Kotlin rounds a tie up, as Java's Math.round does: the floor of x + 0.5
			"roundToInt", "roundToLong" ->
				mathCall("floor", Expression.binary(value, BinaryOperator.ADD, Expression.NumberLiteral("0.5")))
			"compareTo" -> helper(Helper.COMPARE, value, body.expression(arguments[0]))
			else -> null
		}
	}

	/** A function of Lua's `math` library, called on the values. */
	fun mathCall(name: String, vararg arguments: Expression): Expression =
		Expression.Call(mathField(name), arguments.toList())

	/** `java.lang.Math`'s functions that Lua's `math` has, and `floorDiv` and `floorMod`, Lua's `//` and `%`. */
	private fun javaMath(name: String, arguments: List<KtExpression>): Expression? = when (name) {
		"min", "max", "abs" -> math(name, arguments)
		"floorDiv" -> Expression.binary(body.expression(arguments[0]), BinaryOperator.FLOOR_DIVIDE,
			body.expression(arguments[1]))
		"floorMod" -> Expression.binary(body.expression(arguments[0]), BinaryOperator.MODULO,
			body.expression(arguments[1]))
		else -> null
	}

	private fun mathField(name: String): Expression {
		unit.useGlobal("math")
		return Expression.field(Expression.name("math"), name)
	}

	private fun math(name: String, arguments: List<KtExpression>): Expression {
		unit.useGlobal("math")
		val luaName = name.removeSuffix("Of")
		return Expression.Call(Expression.field(Expression.name("math"), luaName), arguments.map(body::expression))
	}

	/**
	 * A value a collection stores. Lua tables cannot hold nil: storing it removes the element, so a literal `null` is
	 * refused.
	 */
	private fun element(value: KtExpression): Expression {
		if (value is KtConstantExpression && value.node.elementType == KtNodeTypes.NULL) {
			throw Rejection(value, "Lua tables cannot hold nil, so a list, map or set cannot store null.")
		}
		return body.expression(value)
	}

	/**
	 * Map keys and set elements are compared by identity in Lua, so neither a data class nor a class that defines
	 * `equals` can be one, and neither can a value the client hands out, a new proxy each time it is read. An API
	 * enum's constants are plain Lua values, which make good keys.
	 */
	private fun checkKey(key: KtExpression): Unit = checkKey(key, type(key))

	/** A key's check, by the type of the keys, as for the elements a chain's `toSet()` collects. */
	fun checkKey(where: PsiElement, type: KaType?): Unit = with(unit.session) {
		val symbol = type?.expandedSymbol ?: return
		val isData = (symbol as? KaNamedClassSymbol)?.isData == true
		if (types.isPair(type) || unit.isPluginType(symbol) && (isData || declaresEquals(symbol))) {
			throw Rejection(where, "Lua compares table keys by identity, so value-equal keys would not match; key the"
				+ " map by a string or number.")
		}
		if (body.api.isApi(symbol) && symbol.classKind != KaClassKind.ENUM_CLASS) {
			val name = unit.annotations.value(symbol, Annotations.LUA_TYPE) ?: symbol.classId?.shortClassName
			throw Rejection(where, "The client hands out a new $name object each time one is read, and Lua"
				+ " compares table keys by identity, so the map or set would not find it again; key it by a number or"
				+ " string the value carries, such as its id or uid.")
		}
	}

	private fun declaresEquals(type: KaClassSymbol): Boolean = with(unit.session) {
		type.declaredMemberScope.callables.any { it.callableId?.callableName?.asString() == "equals" }
	}

	/** A position Kotlin counts from 0 as Lua's, which counts from 1. */
	private fun position(index: KtExpression): Expression = body.position(index)

	/** `map[key]` or `set[element]`. */
	private fun entry(table: Receiver, key: KtExpression): Expression =
		Expression.Index(body.expression(table), body.expression(key))

	private fun present(entry: Expression): Expression =
		Expression.binary(entry, BinaryOperator.NOT_EQUAL, Expression.Nil())

	/** Whether a map or set is empty: Lua's `next` finds no first key. */
	private fun empty(table: Expression): Expression =
		Expression.binary(Expression.call(Expression.name("next"), table), BinaryOperator.EQUAL, Expression.Nil())

	private fun notEmpty(table: Expression): Expression =
		Expression.binary(Expression.call(Expression.name("next"), table), BinaryOperator.NOT_EQUAL, Expression.Nil())

	/** A map's `keys`, `values` or `entries`, which Lua walks on the map itself. */
	class MapView(val map: KtExpression, val selector: String)

	/** The map view a collection is, or null when it is none. */
	fun mapView(collection: KtExpression): MapView? {
		val qualified = collection as? KtDotQualifiedExpression ?: return null
		val selector = (qualified.selectorExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
		val receiverType = type(qualified.receiverExpression)
		if (selector !in setOf("keys", "values", "entries") || receiverType == null || !types.isMap(receiverType)) {
			return null
		}
		return MapView(qualified.receiverExpression, selector)
	}

	private fun isSpread(value: KtExpression): Boolean = (value.parent as? KtValueArgument)?.getSpreadElement() != null

	private fun isCall(expression: KtExpression): Boolean =
		expression is KtCallExpression || (expression as? KtQualifiedExpression)?.selectorExpression is KtCallExpression

	/**
	 * The receiver's expression, for a member that reads the expression as written; one left unnamed is refused, as it
	 * has none.
	 */
	private fun written(receiver: Receiver, where: KtExpression, member: String): KtExpression = when (receiver) {
		is Receiver.Written -> receiver.expression
		is Receiver.Unnamed -> throw Rejection(where, "Oneiro reads $member only on a receiver the code names; write"
			+ " this.$member here.")
	}

	private fun tableCall(name: String, vararg arguments: Expression): Expression {
		unit.useGlobal("table")
		return Expression.Call(Expression.field(Expression.name("table"), name), arguments.toList())
	}

	private fun helper(helper: Helper, vararg arguments: Expression): Expression {
		unit.use(helper)
		return helper.invoke(*arguments)
	}

	private fun type(expression: KtExpression): KaType? = with(unit.session) { expression.expressionType }

	private fun type(receiver: Receiver): KaType? = receiver.type(unit.session)

	/** Whether a type is a regular expression's, Kotlin's `Regex` or Java's `Pattern` and `Matcher`. */
	private fun isRegex(type: KaType?): Boolean =
		isRegex(with(unit.session) { type?.expandedSymbol?.classId?.asFqNameString() })

	private fun isRegex(className: String?): Boolean =
		className == "kotlin.text.Regex" || className?.startsWith("java.util.regex.") == true

	private fun display(member: KaCallableSymbol): String {
		val id = member.callableId ?: return "this"
		return (id.classId?.asFqNameString() ?: id.packageName.asString()) + "." + id.callableName.asString()
	}

	companion object {

		const val STATEMENT_ONLY = "In Lua %s is a statement, not a value; call it on its own line."

		private const val CHAIN_IN_STATEMENT = "In Lua a chain of collection functions is a loop, which can run" +
			" before the code around it only where nothing would see the difference; give its value to a local of its" +
			" own first, val x = ..."

		/** What Lua makes of a chain of collection functions, for a message. */
		const val CHAIN_LOOP = "In Lua a chain of collection functions is a loop"

		/** Why the Lua of a member reads its receiver or an argument twice, for a message. */
		const val READ_TWICE = "Lua reads this value twice"

		/**
		 * The variables of a loop over a map's entries, `for key, value in pairs(map)`, as Lua writes them: a key the
		 * body never reads is `_`, and a value it never reads is left out.
		 */
		fun entryVariables(key: String, value: String, body: Block): List<String> {
			val keyName = if (NameUses.mentions(body, key)) key else "_"
			return if (NameUses.mentions(body, value)) listOf(keyName, value) else listOf(keyName)
		}

		/** The parts of a `Pair` and a `Triple`, by their position, which their tables hold under these names. */
		val PAIR_PARTS = listOf("first", "second", "third")

		/** The classes of pairs, which Lua holds as tables of their parts. */
		private val PAIRS = setOf("kotlin.Pair", "kotlin.Triple")

		/** The limits of Kotlin's integers, which Lua's integers, of 64 bits, hold. */
		private val LIMITS: Map<String, (LibraryCalls) -> Expression> = mapOf(
			"kotlin.Int.Companion.MAX_VALUE" to { _ -> Expression.integer(Int.MAX_VALUE.toLong()) },
			"kotlin.Int.Companion.MIN_VALUE" to { _ -> Expression.integer(Int.MIN_VALUE.toLong()) },
			"kotlin.Long.Companion.MAX_VALUE" to { calls -> calls.mathField("maxinteger") },
			"kotlin.Long.Companion.MIN_VALUE" to { calls -> calls.mathField("mininteger") },
		)

		/** The elements `table.concat` joins as Kotlin writes them: strings and numbers. */
		val JOINABLE = setOf(StandardClassIds.String, StandardClassIds.Char, StandardClassIds.Int,
			StandardClassIds.Long, StandardClassIds.Short, StandardClassIds.Byte, StandardClassIds.Double,
			StandardClassIds.Float)

		/** The functions of `kotlin.math` that are Lua's own under the same name. */
		private val MATH = setOf("abs", "min", "max")

		/** Each array of a primitive type, with the value a new one is filled with. */
		private val PRIMITIVE_ARRAYS = mapOf(
			"kotlin.IntArray" to Expression.integer(0),
			"kotlin.LongArray" to Expression.integer(0),
			"kotlin.ShortArray" to Expression.integer(0),
			"kotlin.ByteArray" to Expression.integer(0),
			"kotlin.DoubleArray" to Expression.NumberLiteral("0.0"),
			"kotlin.FloatArray" to Expression.NumberLiteral("0.0"),
			"kotlin.BooleanArray" to Expression.BooleanLiteral(false),
			"kotlin.CharArray" to Expression.string("\u0000"),
		)

		/** `#list`, the length of a sequence. */
		fun length(list: Expression): Expression = Expression.Unary(UnaryOperator.LENGTH, list)

		/** `#list + 1`, where a new last element goes. */
		fun nextIndex(list: Expression): Expression =
			Expression.binary(length(list), BinaryOperator.ADD, Expression.integer(1))
	}
}

/**
 * The arguments of a call of the library, in the order of the parameters they go to, leaving out those the call leaves
 * to their defaults.
 *
 * @property where the call, where a problem with its arguments is reported
 * @property byName each argument by its parameter's name, the first value for a vararg parameter
 */
internal class LibraryArguments(
	val where: KtCallExpression,
	arguments: List<KtExpression>,
	val byName: Map<String, KtExpression>,
) : List<KtExpression> by arguments
