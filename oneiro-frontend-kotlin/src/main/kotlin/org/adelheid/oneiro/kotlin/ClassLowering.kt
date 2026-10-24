package org.adelheid.oneiro.kotlin

import com.intellij.psi.PsiElement
import org.adelheid.oneiro.lua.BinaryOperator
import org.adelheid.oneiro.lua.Block
import org.adelheid.oneiro.lua.Comment
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.FunctionBody
import org.adelheid.oneiro.lua.Statement
import org.adelheid.oneiro.lua.Trivia
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry

/**
 * The members of objects, classes and enums, as module members. An object's functions and properties belong to its
 * table, and its private ones are locals. A class's functions are methods its objects look up in the table, called with
 * a colon; its private ones are locals that take the object first; and `Class.new` makes an object, giving each
 * property its value in declaration order and running the `init` blocks among them. A companion object's members are
 * the class table's own. A simple enum is a table of its constants, each its own name. A nested class, object or enum
 * is a local table of the file, also a field of the table around it, and an inner class's objects keep the object they
 * belong to as `outer`.
 */
internal class ClassLowering(private val unit: FileLowering) {

	private val functions = Functions(unit)
	private val inherited = InheritedMembers(unit)

	/**
	 * A class's or object's members, with its constants first: a `const val` has no effects and uses nothing, so it
	 * goes where Lua authors put constants, ahead of the code that uses them.
	 */
	private fun constantsFirst(members: List<Member>): List<Member> {
		val constants = members.filter { member ->
			(member.elements().singleOrNull() as? KtProperty)?.hasModifier(KtTokens.CONST_KEYWORD) == true
		}
		return constants + (members - constants.toSet())
	}

	fun members(declaration: KtClassOrObject): List<Member> = when {
		declaration is KtObjectDeclaration -> staticMembers(declaration, declaration.name!!)
		declaration is KtClass && declaration.isInterface() -> interfaceMembers(declaration)
		declaration is KtClass && !declaration.isEnum() -> classMembers(declaration)
		else -> throw Rejection.notYet(declaration, "This kind of declaration")
	}

	/** A class's table: `{}`, or for a subclass a table that looks up what it lacks in its superclass's. */
	fun classTable(declaration: KtClassOrObject): Expression {
		val superclass = superclass(declaration) ?: return Expression.Table(listOf())
		val metatable = Expression.Table(listOf(Expression.TableField.Named("__index",
			unit.moduleReference(superclass))))
		return Expression.call(Expression.name("setmetatable"), Expression.Table(listOf()), metatable)
	}

	/** The class of the plugin's that a class extends, or null when it extends none. */
	private fun superclass(declaration: KtClassOrObject): KaClassSymbol? = with(unit.session) {
		val symbol = declaration.classSymbol ?: return null
		val supertypes = symbol.superTypes.mapNotNull { it.expandedSymbol }
		if (supertypes.any { it.classId?.asFqNameString() in ITERATORS }) {
			throw Rejection(declaration, "Lua's for loop walks tables, not iterator objects; give the class a function"
				+ " that returns its list, and loop over that.")
		}
		for (type in supertypes.filter { it.classKind == KaClassKind.CLASS }) {
			if (unit.isPluginType(type)) {
				return type
			}
			// Every class extends Any, and every enum Enum, which have no Lua of their own
			if (type.classId != StandardClassIds.Any && type.classId != StandardClassIds.Enum) {
				if (!ApiCalls(unit).isApi(type)) {
					throw Rejection(declaration, "Lua has none of ${type.classId?.shortClassName}'s code to inherit;"
						+ " keep one in a property instead of extending it.")
				}
				throw Rejection.notYet(declaration, "Extending ${type.classId?.asFqNameString()}")
			}
		}
		null
	}

	/** A simple enum's table: each constant is its own name, which reads well in logs and compares as text. */
	fun enumTable(declaration: KtClass): Expression.Table {
		val fields = mutableListOf<Expression.TableField>()
		for (member in declaration.declarations) {
			if (member !is KtEnumEntry || member.body != null || member.initializerList != null) {
				throw Rejection.notYet(member, "An enum with members or values of its own")
			}
			val name = member.name!!
			val field: Expression.TableField = Expression.TableField.Named(name, Expression.string(name))
			// A comment after a constant goes above it with the others
			val comments = declarationComments(member) + listOfNotNull(unit.source.trailing(member)).map(Comment::line)
			fields += if (comments.isEmpty()) field else Expression.TableField.Commented(comments, field)
		}
		return Expression.Table(fields)
	}

	/** The members of an object, or of a class's companion object: each belongs to the table, or is a local. */
	private fun staticMembers(declaration: KtClassOrObject, table: String): List<Member> {
		val members = mutableListOf<Member>()
		val nested = mutableListOf<Member>()
		for (member in declaration.declarations) {
			try {
				when (member) {
					is KtNamedFunction -> members += functions.static(member, table, isPrivate(member))
					is KtProperty -> members += staticProperty(member, table)
					is KtClassOrObject -> nested += nestedType(member, declaration, table)
					is KtAnonymousInitializer -> members += staticInitializer(member)
					else -> throw Rejection.notYet(member, "This member of an object")
				}
			} catch (rejection: Rejection) {
				unit.reject(rejection)
			}
		}
		return nested + constantsFirst(members)
	}

	/** An object's `init` block: code that runs where it stands while the module loads. */
	private fun staticInitializer(initializer: KtAnonymousInitializer): Member {
		val code = initializer.body as? KtBlockExpression
			?: throw Rejection(initializer, "An init block without a body does not compile.")
		val lowered = BodyLowering(unit, null, false).block(code)
		val entries = lowered.entries().toMutableList()
		if (entries.isNotEmpty()) {
			val first = entries.first()
			val trivia = Trivia(unit.source.blankLineBefore(initializer),
				unit.source.leading(initializer) + first.trivia().leading(), first.trivia().trailing())
			entries[0] = Block.Entry(first.statement(), trivia, first.origin())
		}
		return Member(listOf(initializer), false, null, entries, listOf(), References.of(unit, initializer))
	}

	private fun staticProperty(property: KtProperty, table: String): Member {
		val initializer = property.initializer ?: throw Rejection.notYet(property, "A property without a value")
		val value = BodyLowering(unit, null, false).expression(initializer)
		val trivia = Trivia(unit.source.blankLineBefore(property), declarationComments(property), null)
		val origin = unit.source.position(unit.codeStart(property))
		if (isPrivate(property)) {
			val name = unit.names.luaName(property.name!!)
			val local = Block.Entry(Statement.Local(listOf(name), listOf(value)), trivia, origin)
			val assigned = Block.Entry(Statement.assignment(Expression.name(name), value), trivia, origin)
			return Member(listOf(property), false, name, listOf(local), listOf(assigned), References.of(unit, property))
		}
		val field = Expression.field(Expression.name(table), unit.memberName(property))
		val assignment = Statement.assignment(field, value)
		return Member(listOf(property), false, null, listOf(Block.Entry(assignment, trivia, origin)), listOf(),
			References.of(unit, property))
	}

	/**
	 * A class's members: `Class.new`, its methods, and its companion object's members, after its nested types', which
	 * Lua must define before the code that uses them.
	 */
	private fun classMembers(declaration: KtClass): List<Member> {
		val table = declaration.name!!
		val members = mutableListOf<Member>()
		val nested = mutableListOf<Member>()
		var constructorPlaced = false
		for (member in declaration.declarations) {
			try {
				when (member) {
					is KtProperty, is KtAnonymousInitializer -> if (!constructorPlaced) {
						members += constructor(declaration)
						constructorPlaced = true
					}
					// An abstract function has no code, which its subclasses give
					is KtNamedFunction -> if (!member.hasModifier(KtTokens.ABSTRACT_KEYWORD)) {
						members += functions.method(member, table, isPrivate(member))
					}
					is KtObjectDeclaration -> if (member.isCompanion()) {
						members += staticMembers(member, table)
					} else {
						nested += nestedType(member, declaration, table)
					}
					is KtClass -> nested += nestedType(member, declaration, table)
					else -> throw Rejection.notYet(member, "This member of a class")
				}
			} catch (rejection: Rejection) {
				unit.reject(rejection)
			}
		}
		if (!constructorPlaced) {
			members.add(0, constructor(declaration))
		}
		members += inherited.defaults(declaration, table)
		members += inherited.metamethods(declaration, table)
		return nested + constantsFirst(members)
	}

	/**
	 * An interface's members: its default functions, as methods of its table that the classes implementing it copy,
	 * and its companion object's members. Its abstract functions and properties have no code.
	 */
	private fun interfaceMembers(declaration: KtClass): List<Member> {
		val table = declaration.name!!
		val members = mutableListOf<Member>()
		val nested = mutableListOf<Member>()
		for (member in declaration.declarations) {
			try {
				when {
					member is KtNamedFunction && member.hasBody() ->
						members += functions.method(member, table, isPrivate(member))
					member is KtNamedFunction -> {}
					member is KtProperty && member.initializer == null && member.getter == null -> {}
					member is KtObjectDeclaration && member.isCompanion() -> members += staticMembers(member, table)
					member is KtClassOrObject -> nested += nestedType(member, declaration, table)
					else -> throw Rejection.notYet(member, "This member of an interface")
				}
			} catch (rejection: Rejection) {
				unit.reject(rejection)
			}
		}
		return nested + constantsFirst(members)
	}

	/**
	 * A class, object or enum declared inside another: its table, a local of the file, then its members, which come
	 * after that table.
	 */
	private fun nestedType(nested: KtClassOrObject, outer: KtClassOrObject, outerTable: String): List<Member> {
		val empty = Expression.Table(listOf())
		return when {
			// A plain table, built where it is constructed, and a fun interface, whose values are Lua functions
			nested is KtClass && (nested.isPlainData() || nested.hasModifier(KtTokens.FUN_KEYWORD)) -> listOf()
			// An interface of abstract functions alone is a type, which has no Lua
			nested is KtClass && nested.isInterface() && nested.declarations.all { it is KtNamedFunction &&
				!it.hasBody() } -> listOf()
			nested is KtClass && nested.isInterface() -> listOf(tableMember(nested, outer, outerTable, empty, false)) +
				interfaceMembers(nested).map { it.after(nested) }
			nested is KtClass && nested.isEnum() && isObjectEnum(nested) ->
				listOf(tableMember(nested, outer, outerTable, empty, true)) +
					objectEnumMembers(nested).map { it.after(nested) }
			nested is KtClass && nested.isEnum() -> listOf(tableMember(nested, outer, outerTable, enumTable(nested),
				false))
			nested is KtClass -> listOf(tableMember(nested, outer, outerTable, classTable(nested), true)) +
				classMembers(nested).map { it.after(nested) }
			else -> listOf(tableMember(nested, outer, outerTable, empty, false)) +
				staticMembers(nested, nested.name!!).map { it.after(nested) }
		}
	}

	/**
	 * A nested type's table as a local, `local Cell = {}`, with `Cell.__index = Cell` for a class with objects, and
	 * `Outer.Cell = Cell` unless it is private.
	 */
	private fun tableMember(nested: KtClassOrObject, outer: KtClassOrObject, outerTable: String, value: Expression,
		instanceClass: Boolean): Member {
		val local = nested.name!!
		val origin = unit.source.position(unit.codeStart(nested))
		val rest = mutableListOf<Block.Entry>()
		if (instanceClass) {
			val table = Expression.name(local)
			val index = Statement.assignment(Expression.field(table, "__index"), table)
			rest += Block.Entry(index, Trivia.NONE, origin)
		}
		if (!isPrivate(nested)) {
			val exported = Statement.assignment(Expression.field(Expression.name(outerTable), local),
				Expression.name(local))
			rest += Block.Entry(exported, Trivia.NONE, origin)
		}
		val trivia = Trivia(unit.source.blankLineBefore(nested), declarationComments(nested), null)
		val declared = listOf(Block.Entry(Statement.Local(listOf(local), listOf(value)), trivia, origin)) + rest
		val assigned = listOf(Block.Entry(Statement.assignment(Expression.name(local), value), trivia, origin)) + rest
		val after: Set<PsiElement> = if (outer.isTopLevel()) setOf() else setOf(outer)
		return Member(listOf(nested), false, local, declared, assigned, setOf(), after)
	}

	/**
	 * `Class.new`: a new object with the class as its metatable, the primary constructor's properties set from its
	 * parameters, then each property's value and each `init` block, in declaration order. An inner class's
	 * constructor takes the object it belongs to first and keeps it as `outer`; a subclass's object starts as its
	 * superclass makes one.
	 *
	 * @param kept the values the constructor takes first and keeps under their names, as an object enum's constant
	 *     keeps its `name` and `ordinal`
	 */
	private fun constructor(declaration: KtClass, kept: List<String> = listOf()): Member {
		val body = BodyLowering(unit, declaration, true)
		val self = Expression.name("self")
		val entries = mutableListOf<Block.Entry>()
		val superclass = superclass(declaration)
		val created = if (superclass == null) {
			Expression.Table(listOf())
		} else {
			val superCall = declaration.superTypeListEntries.filterIsInstance<KtSuperTypeCallEntry>().firstOrNull()
			val arguments = superCall?.valueArguments?.map {
				body.expression(it.getArgumentExpression() ?: throw Rejection(superCall, "This argument is empty."))
			} ?: listOf()
			Expression.Call(Expression.field(unit.moduleReference(superclass), "new"), arguments)
		}
		val metatable = Expression.call(Expression.name("setmetatable"), created, Expression.name(declaration.name!!))
		entries += Block.Entry.of(Statement.Local(listOf("self"), listOf(metatable)))
		val parameters = kept.toMutableList()
		for (value in kept) {
			entries += Block.Entry.of(Statement.assignment(Expression.field(self, value), Expression.name(value)))
		}
		// A parameter a call leaves out gets its default before the object is made
		entries.addAll(0, functions.defaults(declaration.primaryConstructorParameters, body).map(Block.Entry::of))
		if (declaration.isInner()) {
			parameters += "outer"
			entries += Block.Entry.of(Statement.assignment(Expression.field(self, "outer"), Expression.name("outer")))
		}
		for (parameter in declaration.primaryConstructorParameters) {
			val name = unit.localName(parameter, parameter.name!!)
			parameters += name
			if (parameter.hasValOrVar()) {
				entries += Block.Entry.of(Statement.assignment(Expression.field(self, unit.memberName(parameter)),
					Expression.name(name)))
			}
		}
		// The comments after an init block's last statement lead the statement after it
		val pending = mutableListOf<Comment>()
		for (member in declaration.declarations) {
			when (member) {
				is KtProperty -> {
					val initializer = member.initializer ?: continue
					val value = body.expression(initializer)
					val assignment = Statement.assignment(Expression.field(self, unit.memberName(member)), value)
					val trivia = Trivia(false, declarationComments(member), unit.source.trailing(member))
					add(entries, listOf(Block.Entry(assignment, trivia, unit.source.position(unit.codeStart(member)))),
						pending)
				}
				is KtAnonymousInitializer -> {
					val block = member.body as? KtBlockExpression
						?: throw Rejection(member, "An init block without a body does not compile.")
					val lowered = body.block(block)
					// The comments above the block come before those above its first statement
					pending += unit.source.leading(member)
					add(entries, initializerEntries(lowered, unit.source.blankLineBefore(member)), pending)
					pending += lowered.closingComments()
				}
				else -> {}
			}
		}
		add(entries, listOf(Block.Entry.of(Statement.Return(listOf(self)))), pending)
		val function = Statement.FunctionDeclaration(listOf(declaration.name!!, "new"), null,
			FunctionBody.of(parameters, Block(entries, listOf())))
		// A nested class's own key is its table's
		val keys: List<PsiElement> =
			listOfNotNull(declaration.takeIf { it.isTopLevel() }, declaration.primaryConstructor)
		val origin = unit.source.position(unit.codeStart(declaration))
		val trivia = Trivia(false, constructorComments(declaration), null)
		return Member(keys, true, null, listOf(Block.Entry(function, trivia, origin)), listOf(),
			References.of(unit, declaration.primaryConstructor) +
				declaration.declarations.filter { it is KtProperty || it is KtAnonymousInitializer }
					.flatMap { References.of(unit, it) })
	}

	/**
	 * The annotations of a constructor's parameters, when the class's KDoc describes them with `@param` or
	 * `@property`, as Kotlin documents a primary constructor.
	 */
	private fun constructorComments(declaration: KtClass): List<Comment> {
		val doc = declaration.docComment ?: return listOf()
		val parameters = declaration.primaryConstructorParameters
		if (parameters.none { KDocText.param(doc, it.name!!).isNotEmpty() }) {
			return listOf()
		}
		return functions.parameterAnnotations(parameters, doc)
	}

	/**
	 * An enum whose constants are objects: `Herb.new(name, ordinal, ...)`, which keeps the constant's name and its
	 * place in the enum besides the constructor's properties; each constant, `Herb.GUAM = Herb.new("GUAM", 0, ...)`,
	 * with its body's properties and functions; the enum's own functions; and `__lt`, which orders the constants as
	 * they are declared.
	 */
	fun objectEnumMembers(declaration: KtClass): List<Member> {
		val table = declaration.name!!
		val members = mutableListOf(constructor(declaration, listOf("name", "ordinal")))
		val nested = mutableListOf<Member>()
		var ordinal = 0L
		for (member in declaration.declarations) {
			try {
				when (member) {
					is KtEnumEntry -> members += enumConstant(declaration, member, ordinal++)
					is KtNamedFunction -> if (!member.hasModifier(KtTokens.ABSTRACT_KEYWORD)) {
						members += functions.method(member, table, isPrivate(member))
					}
					is KtProperty -> {}
					is KtObjectDeclaration -> if (member.isCompanion()) {
						members += staticMembers(member, table)
					} else {
						nested += nestedType(member, declaration, table)
					}
					is KtClass -> nested += nestedType(member, declaration, table)
					else -> throw Rejection.notYet(member, "This member of an enum")
				}
			} catch (rejection: Rejection) {
				unit.reject(rejection)
			}
		}
		val lessThan = Expression.binary(Expression.field(Expression.name("a"), "ordinal"), BinaryOperator.LESS,
			Expression.field(Expression.name("b"), "ordinal"))
		val order = Statement.FunctionDeclaration(listOf(table, "__lt"), null,
			FunctionBody.of(listOf("a", "b"), Block.of(Statement.Return(listOf(lessThan)))))
		val comment = Comment.line("Orders the constants as they are declared, as Kotlin's natural order does")
		members += Member(listOf(), true, null, listOf(Block.Entry(order, Trivia(true, listOf(comment), null), null)))
		return nested + members
	}

	/**
	 * One constant of an object enum, `Herb.GUAM = Herb.new("GUAM", 0, 5291, 9)`; a body's properties are set on it,
	 * and its functions, which replace the enum's, are its own.
	 */
	private fun enumConstant(declaration: KtClass, constant: KtEnumEntry, ordinal: Long): List<Member> {
		val table = Expression.name(declaration.name!!)
		val name = constant.name!!
		val body = BodyLowering(unit, null, false)
		val arguments = constant.initializerList?.initializers?.filterIsInstance<KtSuperTypeCallEntry>()?.firstOrNull()
			?.valueArguments?.map {
				body.expression(it.getArgumentExpression() ?: throw Rejection(constant, "This argument is empty."))
			}
			?: listOf()
		val created = Expression.Call(Expression.field(table, "new"),
			listOf(Expression.string(name), Expression.integer(ordinal)) + arguments)
		val field = Expression.field(table, name)
		val trivia = Trivia(unit.source.blankLineBefore(constant), declarationComments(constant),
			unit.source.trailing(constant))
		val origin = unit.source.position(unit.codeStart(constant))
		val entries = mutableListOf(Block.Entry(Statement.assignment(field, created), trivia, origin))
		val members = mutableListOf<Member>()
		val inner = BodyLowering(unit, constant, true)
		for (member in constant.body?.declarations ?: listOf()) {
			when (member) {
				is KtProperty -> {
					val value = member.initializer ?: throw Rejection.notYet(member, "A property without a value")
					val assignment = Statement.assignment(Expression.field(field, unit.memberName(member)),
						inner.expression(value))
					entries += Block.Entry(assignment, Trivia(false, declarationComments(member),
						unit.source.trailing(member)), unit.source.position(unit.codeStart(member)))
				}
				is KtNamedFunction -> members += functions.constantMethod(member, listOf(declaration.name!!, name))
				else -> throw Rejection.notYet(member, "This member of an enum constant's body")
			}
		}
		val uses: Set<PsiElement> = setOfNotNull(declaration.primaryConstructor ?: declaration) +
			References.of(unit, constant)
		return listOf(Member(listOf(constant), false, null, entries, uses = uses)) + members
	}

	/** An init block's statements, with the blank line before the block on the first. */
	private fun initializerEntries(lowered: Block, blankLine: Boolean): List<Block.Entry> {
		val entries = lowered.entries().toMutableList()
		if (entries.isEmpty()) {
			return entries
		}
		val first = entries.first()
		entries[0] = Block.Entry(first.statement(), Trivia(blankLine, first.trivia().leading(),
			first.trivia().trailing()), first.origin())
		return entries
	}

	/** Adds the entries, with the pending comments above the first of them, which then stop pending. */
	private fun add(entries: MutableList<Block.Entry>, added: List<Block.Entry>, pending: MutableList<Comment>) {
		if (added.isEmpty()) {
			return
		}
		val first = added.first()
		val trivia = Trivia(first.trivia().blankLineBefore(), pending + first.trivia().leading(),
			first.trivia().trailing())
		entries += Block.Entry(first.statement(), trivia, first.origin())
		entries += added.drop(1)
		pending.clear()
	}

	/** A declaration's comments: those above it, and its KDoc description. */
	private fun declarationComments(declaration: KtDeclaration): List<Comment> =
		unit.source.leading(declaration) +
			listOfNotNull(declaration.docComment).flatMap(KDocText::description).map(Comment::line)

	private fun isPrivate(declaration: KtDeclaration): Boolean = declaration.hasModifier(KtTokens.PRIVATE_KEYWORD)

	private fun isObjectEnum(declaration: KtClass): Boolean =
		with(unit.session) { declaration.classSymbol }?.let(unit::isObjectEnum) == true
}

/**
 * Whether a class is a data class of properties alone, which in Lua is a plain table built where it is constructed, as
 * hand-written Lua passes tables around: no members besides its properties, and no supertypes.
 */
internal fun KtClass.isPlainData(): Boolean =
	isData() && declarations.isEmpty() && superTypeListEntries.isEmpty()

/** The library's iterable and iterator types, which no class of the plugin's can be, as Lua's for loop walks tables. */
private val ITERATORS = setOf("kotlin.collections.Iterable", "kotlin.collections.MutableIterable",
	"kotlin.collections.Iterator", "kotlin.collections.MutableIterator", "kotlin.collections.Collection",
	"kotlin.collections.MutableCollection", "kotlin.collections.List", "kotlin.collections.MutableList",
	"kotlin.collections.Set", "kotlin.collections.MutableSet", "kotlin.sequences.Sequence", "java.lang.Iterable",
	"java.util.Iterator")
