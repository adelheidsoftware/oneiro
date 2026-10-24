package org.adelheid.oneiro.java;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

/**
 * Checks on declarations that produce little or no Lua of their own: what a class may extend and implement, a
 * {@code @LuaModule} declaration, which only stands for what a Lua module defines, the records in one, and an
 * interface of abstract methods only, which is a type and nothing more.
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
				throw new Rejection(superclass, element.getSimpleName() + " stands for Lua the client provides, which a"
						+ " class of the plugin's cannot extend.");
			}
			if (!types.isPluginType(element)) {
				throw new Rejection(superclass, "Lua has none of " + element.getSimpleName()
						+ "'s Java code to inherit; keep one in a field instead of extending it.");
			}
			if (types.declaredModule(element) != null) {
				throw new Rejection(superclass, "A @LuaModule declaration stands for a hand-written module, which Java"
						+ " code cannot extend.");
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
	 * A {@code @LuaModule} declaration produces no Lua; its members only stand for what the Lua module defines: native
	 * methods, fields without values, and records, which describe the tables and the several values it returns.
	 */
	void checkDeclaration(ClassTree type) {
		for (Tree member : type.getMembers()) {
			if (unit.isSynthetic(member)) {
				continue;
			}
			try {
				switch (member) {
					case MethodTree method when UnitLowering.isConstructor(method) -> {
						if (!method.getModifiers().getFlags().contains(Modifier.PRIVATE)
								|| !method.getParameters().isEmpty()
								|| !unit.constructors().realStatements(method.getBody()).isEmpty()) {
							unit.error(unit.start(method), "A @LuaModule declaration has no constructor of its own;"
									+ " declare the module's constructor as a static native method with"
									+ " @LuaName(\"new\").");
						}
					}
					case MethodTree method -> {
						if (!method.getModifiers().getFlags().contains(Modifier.NATIVE)) {
							unit.error(unit.start(method), "Every method of a @LuaModule declaration is native: it"
									+ " stands for a function the Lua module defines.");
						}
						unit.luaMemberName(method);
					}
					case VariableTree field -> {
						if (field.getInitializer() != null) {
							unit.error(unit.start(field), "A @LuaModule declaration's fields take their values from the"
									+ " Lua module; remove the initializer.");
						}
						unit.luaMemberName(field);
					}
					case ClassTree nested when nested.getKind() == Tree.Kind.RECORD -> checkRecord(nested);
					default -> unit.reject(Rejection.notYet(member, "This member in a @LuaModule declaration"));
				}
			} catch (Rejection rejection) {
				unit.reject(rejection);
			}
		}
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
