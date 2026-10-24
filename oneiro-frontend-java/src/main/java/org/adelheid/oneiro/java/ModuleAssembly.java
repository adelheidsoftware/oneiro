package org.adelheid.oneiro.java;

import static org.adelheid.oneiro.lua.Expression.call;
import static org.adelheid.oneiro.lua.Expression.field;
import static org.adelheid.oneiro.lua.Expression.name;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import org.adelheid.oneiro.lua.Block;
import org.adelheid.oneiro.lua.Comment;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.Statement;
import org.adelheid.oneiro.lua.Trivia;
import org.jspecify.annotations.Nullable;

/**
 * A module's statements in the order Lua needs them. Java defines every method before any code runs, while Lua
 * defines a function when its statement runs. So code that runs while the module loads comes after what it uses, and
 * a local that a function refers to before its own definition is declared ahead. The private members and nested
 * types of every class in the file are locals of one Lua chunk, so their names must differ.
 */
final class ModuleAssembly {

	private final UnitLowering unit;
	private final JavaTypes types;

	ModuleAssembly(UnitLowering unit) {
		this.unit = unit;
		this.types = unit.types;
	}

	/**
	 * The module's statements: the {@code require} lines, the helpers, the module table, any locals declared ahead,
	 * the members, and {@code return Module}. The plugin class's {@code main.lua} has no table and returns nothing. A
	 * local declared ahead, {@code local name}, is assigned where its member's definition stands.
	 */
	List<Block.Entry> assemble(@Nullable Expression table, boolean instanceClass, List<UnitLowering.Member> members) {
		List<UnitLowering.Member> ordered = order(members);
		Map<UnitLowering.Member, Integer> position = new HashMap<>();
		for (int index = 0; index < ordered.size(); index++) {
			position.put(ordered.get(index), index);
		}
		Map<Element, UnitLowering.Member> byElement = byElement(members);
		Set<UnitLowering.Member> declaredAhead = new LinkedHashSet<>();
		for (UnitLowering.Member user : ordered) {
			for (Element reference : user.references()) {
				UnitLowering.Member used = byElement.get(reference);
				if (used != null && used.localName() != null
						&& position.getOrDefault(used, -1) > position.getOrDefault(user, -1)) {
					declaredAhead.add(used);
				}
			}
		}

		List<Block.Entry> entries = new ArrayList<>();
		for (Map.Entry<String, String> require : unit.requires.entrySet()) {
			Expression module = call(name("require"), Expression.string(require.getValue()));
			entries.add(Block.Entry.of(new Statement.Local(List.of(require.getKey()), List.of(module))));
		}
		for (Helper helper : unit.helpers()) {
			Trivia trivia = new Trivia(!entries.isEmpty(), helper.comment(), null);
			entries.add(new Block.Entry(helper.definition(), trivia, null));
		}
		if (table != null) {
			entries.add(new Block.Entry(new Statement.Local(List.of(unit.moduleName), List.of(table)),
					new Trivia(!entries.isEmpty(), List.of(), null), null));
		}
		if (instanceClass) {
			Expression moduleTable = name(unit.moduleName);
			entries.add(Block.Entry.of(Statement.assignment(field(moduleTable, "__index"), moduleTable)));
		}
		if (!declaredAhead.isEmpty()) {
			List<String> locals = declaredAhead.stream().map(UnitLowering.Member::localName).toList();
			Comment note = Comment.line("Declared ahead: used above their definitions below.");
			entries.add(new Block.Entry(new Statement.Local(locals, List.of()),
					new Trivia(!entries.isEmpty(), List.of(note), null), null));
		}
		boolean first = true;
		// A function ends with end, which a blank line separates from whatever follows
		boolean afterFunction = false;
		for (UnitLowering.Member member : ordered) {
			// Declared ahead: assigned here, or, for a field without a value, the declaration stands alone
			List<Block.Entry> memberEntries = declaredAhead.contains(member) ? member.assignForm() : member.entries();
			for (int index = 0; index < memberEntries.size(); index++) {
				Block.Entry entry = memberEntries.get(index);
				boolean blankLine = index == 0 && !entries.isEmpty()
						&& (first || member.function() || afterFunction || entry.trivia().blankLineBefore());
				entries.add(new Block.Entry(entry.statement(),
						new Trivia(blankLine, entry.trivia().leading(), entry.trivia().trailing()), entry.origin()));
			}
			if (!memberEntries.isEmpty()) {
				first = false;
				afterFunction = member.function();
			}
		}
		if (table != null) {
			entries.add(new Block.Entry(new Statement.Return(List.of(name(unit.moduleName))),
					new Trivia(true, List.of(), null), null));
		}
		return entries;
	}

	private static Map<Element, UnitLowering.Member> byElement(List<UnitLowering.Member> members) {
		Map<Element, UnitLowering.Member> byElement = new HashMap<>();
		members.forEach(member -> member.elements().forEach(element -> byElement.put(element, member)));
		return byElement;
	}

	/**
	 * Source order, except that a member comes after the class table its definition assigns into, and code that runs
	 * while the module loads (a field's value, an initializer) comes after everything it uses, including what the
	 * functions it calls use.
	 */
	private static List<UnitLowering.Member> order(List<UnitLowering.Member> members) {
		Placement placement = new Placement(byElement(members));
		members.forEach(placement::place);
		return placement.ordered;
	}

	/** The members in the order {@link #order} gives them, built one placement at a time. */
	private static final class Placement {

		private final Map<Element, UnitLowering.Member> byElement;
		private final List<UnitLowering.Member> ordered = new ArrayList<>();
		private final Set<UnitLowering.Member> started = new HashSet<>();
		private final Set<UnitLowering.Member> usesStarted = new HashSet<>();

		Placement(Map<Element, UnitLowering.Member> byElement) {
			this.byElement = byElement;
		}

		void place(UnitLowering.Member member) {
			// A member already placed, or one being placed further up a cycle, stays where it is
			if (!started.add(member)) {
				return;
			}
			for (Element element : member.after()) {
				UnitLowering.Member needed = byElement.get(element);
				if (needed != null) {
					place(needed);
				}
			}
			if (!member.function()) {
				placeUses(member);
			}
			ordered.add(member);
		}

		/** Places what a member's code uses before that code runs; for a function, what it uses first, then itself. */
		private void placeUses(UnitLowering.Member member) {
			if (!usesStarted.add(member)) {
				return;
			}
			for (Element element : member.references()) {
				UnitLowering.Member used = byElement.get(element);
				if (used == null || used.equals(member)) {
					continue;
				}
				if (used.function()) {
					placeUses(used);
				}
				place(used);
			}
		}
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
