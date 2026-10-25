package org.adelheid.oneiro.kotlin

import com.intellij.psi.PsiElement
import org.adelheid.oneiro.lowering.ModuleMember
import org.adelheid.oneiro.lua.Block

/**
 * One piece of a Kotlin file's module, for [org.adelheid.oneiro.lowering.ModuleLayout], keyed by the declarations it
 * stands for: functions, properties, classes and objects.
 */
internal class Member(
	private val declarations: List<PsiElement>,
	private val isFunction: Boolean,
	private val local: String?,
	private val statements: List<Block.Entry>,
	private val declaredAheadForm: List<Block.Entry> = statements,
	private val uses: Set<PsiElement> = setOf(),
	private val comesAfter: Set<PsiElement> = setOf(),
	private val isType: Boolean = false,
) : ModuleMember<PsiElement> {

	override fun type(): Boolean = isType

	override fun elements(): List<PsiElement> = declarations

	override fun function(): Boolean = isFunction

	override fun localName(): String? = local

	override fun entries(): List<Block.Entry> = statements

	override fun assignForm(): List<Block.Entry> = declaredAheadForm

	override fun references(): Set<PsiElement> = uses

	override fun after(): Set<PsiElement> = comesAfter

	/** The same member, placed after the declaration's members as well: a nested class's after its table. */
	fun after(declaration: PsiElement): Member =
		Member(declarations, isFunction, local, statements, declaredAheadForm, uses, comesAfter + declaration)
}
