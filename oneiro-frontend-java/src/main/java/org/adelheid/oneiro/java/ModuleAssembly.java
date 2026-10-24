package org.adelheid.oneiro.java;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import org.adelheid.oneiro.lowering.ModuleLayout;
import org.adelheid.oneiro.lua.Block;
import org.adelheid.oneiro.lua.Expression;
import org.jspecify.annotations.Nullable;

/**
 * What module assembly reads from javac's trees: the declarations each member's code uses, which
 * {@link ModuleLayout} orders the module by, and the names of the locals the file's classes become. The private
 * members and nested types of every class in the file are locals of one Lua chunk, so their names must differ.
 */
final class ModuleAssembly {

	private final UnitLowering unit;
	private final JavaTypes types;

	ModuleAssembly(UnitLowering unit) {
		this.unit = unit;
		this.types = unit.types;
	}

	/**
	 * The module's statements, laid out by {@link ModuleLayout}: the lines that load the modules it uses, the helpers,
	 * the module table, any locals declared ahead, the members, and {@code return Module}.
	 */
	List<Block.Entry> assemble(@Nullable Expression table, boolean instanceClass, List<UnitLowering.Member> members) {
		return ModuleLayout.assemble(unit.moduleName, table, instanceClass, unit.requires, unit.helpers(), members);
	}

	/**
	 * The declarations of this file that a member's code uses: methods, fields, constructors and nested classes. Using
	 * a member of a nested class also uses that class's table.
	 */
	Set<Element> references(Tree member) {
		Set<Element> found = new HashSet<>();
		new TreePathScanner<Void, Void>() {
			@Override
			public Void visitIdentifier(IdentifierTree node, Void unused) {
				note(unit.trees.getElement(getCurrentPath()));
				return super.visitIdentifier(node, null);
			}

			@Override
			public Void visitMemberSelect(MemberSelectTree node, Void unused) {
				note(unit.trees.getElement(getCurrentPath()));
				return super.visitMemberSelect(node, null);
			}

			@Override
			public Void visitNewClass(NewClassTree node, Void unused) {
				note(unit.trees.getElement(getCurrentPath()));
				return super.visitNewClass(node, null);
			}

			@Override
			public Void visitMemberReference(MemberReferenceTree node, Void unused) {
				note(unit.trees.getElement(getCurrentPath()));
				return super.visitMemberReference(node, null);
			}

			private void note(@Nullable Element element) {
				if (element == null) {
					return;
				}
				if (element instanceof TypeElement type && unit.fileTypes.contains(type)) {
					found.add(type);
				}
				if (element.getEnclosingElement() instanceof TypeElement owner && unit.fileTypes.contains(owner)) {
					found.add(element);
					if (!owner.equals(unit.topLevel)) {
						found.add(owner);
					}
				}
			}
		}.scan(unit.path(member), null);
		return found;
	}

	/**
	 * The private members and nested types of every class in the file become locals of the one Lua chunk, so their
	 * names must differ; a private static field and a private method with one name clash too.
	 */
	void checkLocals(ClassTree top) {
		Map<String, Tree> taken = new HashMap<>();
		taken.put(unit.moduleName, top);
		new TreeScanner<Void, Void>() {
			@Override
			public Void visitClass(ClassTree node, Void unused) {
				if (node.getKind() == Tree.Kind.RECORD || node.getKind() == Tree.Kind.INTERFACE) {
					return null;
				}
				Set<String> methods = new HashSet<>();
				// Every member of the plugin class is a local of main.lua, whatever its visibility
				boolean allLocal = unit.trees.getElement(unit.path(node)) instanceof TypeElement type
						&& type.equals(unit.entry);
				for (Tree member : node.getMembers()) {
					switch (member) {
						case ClassTree nested when nested.getKind() == Tree.Kind.CLASS
								|| nested.getKind() == Tree.Kind.ENUM ->
								claim(taken, nested.getSimpleName().toString(), nested);
						case VariableTree field when types.configType(unit.type(field)) != null -> {
						}
						case VariableTree field when allLocal
								|| (field.getModifiers().getFlags().contains(Modifier.PRIVATE)
										&& UnitLowering.isStatic(field.getModifiers().getFlags())) ->
								claim(taken, unit.names.luaName(field.getName().toString()), field);
						case MethodTree method when !UnitLowering.isConstructor(method) && !unit.isSynthetic(method)
								&& (allLocal ? !UnitLowering.isLifecycle(method)
										: method.getModifiers().getFlags().contains(Modifier.PRIVATE)) -> {
							// The overloads of a default chain are one function
							if (methods.add(method.getName().toString())) {
								claim(taken, unit.names.luaName(method.getName().toString()), method);
							}
						}
						default -> {
						}
					}
				}
				return super.visitClass(node, null);
			}

			// Only class members matter; classes inside code are rejected where they appear
			@Override
			public Void visitMethod(MethodTree node, Void unused) {
				return null;
			}

			@Override
			public Void visitVariable(VariableTree node, Void unused) {
				return null;
			}

			@Override
			public Void visitBlock(BlockTree node, Void unused) {
				return null;
			}
		}.scan(top, null);
	}

	private void claim(Map<String, Tree> taken, String name, Tree declaration) {
		if (taken.putIfAbsent(name, declaration) != null) {
			unit.error(unit.start(declaration), "In Lua, the private members and nested classes of the classes in a"
					+ " file are locals of one scope, so two of them cannot both be named " + name + "; rename one.");
		}
	}
}
