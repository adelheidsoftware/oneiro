package org.adelheid.oneiro.java.core;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import org.adelheid.oneiro.lowering.client.BindingAnnotations;

/**
 * Checks on declarations that produce little or no Lua of their own: what a class may extend and implement, the
 * annotations that only generated bindings carry, records, and an interface of abstract methods only, which is a type
 * and nothing more.
 */
final class DeclarationChecks {

	private final UnitLowering unit;
	private final JavaTypes types;

	DeclarationChecks(UnitLowering unit) {
		this.unit = unit;
		this.types = unit.types;
	}

	/** A class may extend a class of the plugin's and implement the plugin's interfaces; library types have no Lua. */
	void checkHeritage(ClassTree type) {
		Tree superclass = type.getExtendsClause();
		TypeElement own = (TypeElement) unit.element(type);
		boolean isEntry = types.isPluginEntry(own) || types.isDevEntry(own);
		if (isEntry && !own.equals(unit.topLevel)) {
			throw new Rejection(type, "The plugin class becomes main.lua; declare it as a class of its own file.");
		}
		if (superclass != null && !isEntry) {
			TypeElement element = unit.typeElement(superclass);
			if (types.isApi(element)) {
				throw new Rejection(superclass, element.getSimpleName() + " stands for Lua that Oneiro does not write,"
						+ " which a class of the plugin's cannot extend.");
			}
			if (!types.isPluginType(element)) {
				throw new Rejection(superclass, "Lua has none of " + element.getSimpleName()
						+ "'s Java code to inherit; keep one in a field instead of extending it.");
			}
		}
		for (Tree implemented : type.getImplementsClause()) {
			String name = unit.typeElement(implemented).getQualifiedName().toString();
			// Any other library interface is a type only, as the plugin's own abstract ones are
			if (name.equals("java.lang.Iterable") || name.equals("java.util.Iterator")) {
				throw new Rejection(implemented, "Lua's for loop walks tables, not iterator objects; give the class a"
						+ " method that returns its list, and loop over that.");
			}
		}
	}

	/**
	 * Whether the file is free of what only generated bindings carry: the annotations that tell the compiler how a
	 * class meets Lua it does not write, {@code @LuaGlobal}, {@code @LuaModule} and the rest, and native methods. Each
	 * is reported where it is, because the Lua the plugin calls is bound from its LuaCATS annotations instead.
	 */
	boolean checkBindingsOnly(ClassTree type) {
		boolean[] clean = {true};
		new TreeScanner<Void, Void>() {
			@Override
			public Void visitModifiers(ModifiersTree modifiers, Void unused) {
				for (AnnotationTree annotation : modifiers.getAnnotations()) {
					TypeElement element = unit.typeElement(annotation.getAnnotationType());
					if (BindingAnnotations.isBindingAnnotation(element.getQualifiedName().toString())) {
						unit.error(unit.start(annotation), BindingAnnotations.rejection(
								element.getSimpleName().toString()));
						clean[0] = false;
					}
				}
				return super.visitModifiers(modifiers, null);
			}

			@Override
			public Void visitMethod(MethodTree method, Void unused) {
				if (method.getModifiers().getFlags().contains(Modifier.NATIVE)) {
					unit.error(unit.start(method), BindingAnnotations.noBody("A native method"));
					clean[0] = false;
				}
				return super.visitMethod(method, null);
			}
		}.scan(type, null);
		return clean[0];
	}

	/**
	 * A record becomes a plain Lua table, built where the record is constructed, so it produces no Lua of its own.
	 * Only its components carry over; methods, constructors and static members are not supported yet.
	 */
	void checkRecord(ClassTree record) {
		for (Tree member : record.getMembers()) {
			if (unit.isSynthetic(member)) {
				continue;
			}
			boolean component = member instanceof VariableTree field
					&& !UnitLowering.isStatic(field.getModifiers().getFlags());
			if (!component) {
				unit.reject(Rejection.notYet(member, "A record with methods, constructors or static members"));
			} else {
				try {
					unit.luaMemberName(member);
				} catch (Rejection rejection) {
					unit.reject(rejection);
				}
			}
		}
	}

	/** An interface of only abstract methods is a type and produces no Lua; this checks that Lua can use its names. */
	void checkInterface(ClassTree type) {
		for (Tree member : type.getMembers()) {
			if (unit.isSynthetic(member)) {
				continue;
			}
			try {
				unit.luaMemberName(member);
			} catch (Rejection rejection) {
				unit.reject(rejection);
			}
		}
	}

	/** Whether an interface has Lua of its own: constants, methods with bodies, or nested types. */
	boolean hasLua(ClassTree type) {
		return type.getMembers().stream()
				.filter(member -> !unit.isSynthetic(member))
				.anyMatch(member -> member instanceof VariableTree || member instanceof ClassTree
						|| (member instanceof MethodTree method && method.getBody() != null));
	}
}
