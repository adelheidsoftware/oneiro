package org.adelheid.oneiro.kotlin

import com.intellij.psi.PsiElement
import org.adelheid.oneiro.lua.Block
import org.adelheid.oneiro.lua.Comment
import org.adelheid.oneiro.lua.Expression
import org.adelheid.oneiro.lua.FunctionBody
import org.adelheid.oneiro.lua.Statement
import org.adelheid.oneiro.lua.Trivia
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty

/**
 * The members of objects, classes and enums, as module members. An object's functions and properties belong to its
 * table, and its private ones are locals. A class's functions are methods its objects look up in the table, called with
 * a colon; its private ones are locals that take the object first; and `Class.new` makes an object, giving each
 * property its value in declaration order and running the `init` blocks among them. A companion object's members are
 * the class table's own. A simple enum is a table of its constants, each its own name.
 */
internal class ClassLowering(private val unit: FileLowering) {

	private val functions = Functions(unit)

	fun members(declaration: KtClassOrObject): List<Member> = when {
		declaration is KtObjectDeclaration -> staticMembers(declaration, declaration.name!!)
		declaration is KtClass && !declaration.isInterface() && !declaration.isEnum() -> classMembers(declaration)
		else -> throw Rejection.notYet(declaration, "This kind of declaration")
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
			val comments = unit.source.leading(member) + listOfNotNull(member.docComment).flatMap(KDocText::description)
				.map(Comment::line)
			fields += if (comments.isEmpty()) field else Expression.TableField.Commented(comments, field)
		}
		return Expression.Table(fields)
	}

	/** The members of an object, or of a class's companion object: each belongs to the table, or is a local. */
	private fun staticMembers(declaration: KtClassOrObject, table: String): List<Member> {
		val members = mutableListOf<Member>()
		for (member in declaration.declarations) {
			try {
				members += when (member) {
					is KtNamedFunction -> functions.static(member, table, isPrivate(member))
					is KtProperty -> staticProperty(member, table)
					else -> throw Rejection.notYet(member, "This member of an object")
				}
			} catch (rejection: Rejection) {
				unit.reject(rejection)
			}
		}
		return members
	}

	private fun staticProperty(property: KtProperty, table: String): Member {
		val initializer = property.initializer ?: throw Rejection.notYet(property, "A property without a value")
		val value = BodyLowering(unit, null, false).expression(initializer)
		val trivia = Trivia(unit.source.blankLineBefore(property), propertyComments(property), null)
		val origin = unit.source.position(unit.codeStart(property))
		if (isPrivate(property)) {
			val name = unit.names.luaName(property.name!!)
			val local = Block.Entry(Statement.Local(listOf(name), listOf(value)), trivia, origin)
			val assigned = Block.Entry(Statement.assignment(Expression.name(name), value), trivia, origin)
			return Member(listOf(property), false, name, listOf(local), listOf(assigned), References.of(unit, property))
		}
		val assignment = Statement.assignment(Expression.field(Expression.name(table), property.name!!), value)
		return Member(listOf(property), false, null, listOf(Block.Entry(assignment, trivia, origin)), listOf(),
			References.of(unit, property))
	}

	/** A class's members: `Class.new`, its methods, and its companion object's members. */
	private fun classMembers(declaration: KtClass): List<Member> {
		val table = declaration.name!!
		val members = mutableListOf<Member>()
		var constructorPlaced = false
		for (member in declaration.declarations) {
			try {
				when (member) {
					is KtProperty, is KtAnonymousInitializer -> if (!constructorPlaced) {
						members += constructor(declaration)
						constructorPlaced = true
					}
					is KtNamedFunction -> members += functions.method(member, table, isPrivate(member))
					is KtObjectDeclaration -> if (member.isCompanion()) {
						members += staticMembers(member, table)
					} else {
						throw Rejection.notYet(member, "An object inside a class")
					}
					else -> throw Rejection.notYet(member, "This member of a class")
				}
			} catch (rejection: Rejection) {
				unit.reject(rejection)
			}
		}
		if (!constructorPlaced) {
			members.add(0, constructor(declaration))
		}
		return members
	}

	/**
	 * `Class.new`: a new object with the class as its metatable, the primary constructor's properties set from its
	 * parameters, then each property's value and each `init` block, in declaration order.
	 */
	private fun constructor(declaration: KtClass): Member {
		val body = BodyLowering(unit, declaration, true)
		val self = Expression.name("self")
		val entries = mutableListOf<Block.Entry>()
		val metatable = Expression.call(Expression.name("setmetatable"), Expression.Table(listOf()),
			Expression.name(declaration.name!!))
		entries += Block.Entry.of(Statement.Local(listOf("self"), listOf(metatable)))
		val parameters = mutableListOf<String>()
		for (parameter in declaration.primaryConstructorParameters) {
			val name = unit.names.luaName(parameter.name!!)
			parameters += name
			if (parameter.hasValOrVar()) {
				entries += Block.Entry.of(Statement.assignment(Expression.field(self, parameter.name!!),
					Expression.name(name)))
			}
		}
		for (member in declaration.declarations) {
			when (member) {
				is KtProperty -> {
					val initializer = member.initializer ?: continue
					val value = body.expression(initializer)
					val assignment = Statement.assignment(Expression.field(self, member.name!!), value)
					val trivia = Trivia(false, propertyComments(member), unit.source.trailing(member))
					entries += Block.Entry(assignment, trivia, unit.source.position(unit.codeStart(member)))
				}
				is KtAnonymousInitializer -> {
					val block = member.body as? KtBlockExpression
						?: throw Rejection(member, "An init block without a body does not compile.")
					entries += body.block(block).entries()
				}
				else -> {}
			}
		}
		entries += Block.Entry.of(Statement.Return(listOf(self)))
		val function = Statement.FunctionDeclaration(listOf(declaration.name!!, "new"), null,
			FunctionBody.of(parameters, Block(entries, listOf())))
		val keys: List<PsiElement> = listOfNotNull(declaration, declaration.primaryConstructor)
		val origin = unit.source.position(unit.codeStart(declaration))
		return Member(keys, true, null, listOf(Block.Entry(function, Trivia.NONE, origin)), listOf(),
			References.of(unit, declaration.primaryConstructor) +
				declaration.declarations.filter { it is KtProperty || it is KtAnonymousInitializer }
					.flatMap { References.of(unit, it) })
	}

	/** A property's comments: those above it, and its KDoc description. */
	private fun propertyComments(property: KtProperty): List<Comment> =
		unit.source.leading(property) +
			listOfNotNull(property.docComment).flatMap(KDocText::description).map(Comment::line)

	private fun isPrivate(declaration: KtDeclaration): Boolean = declaration.hasModifier(KtTokens.PRIVATE_KEYWORD)
}
