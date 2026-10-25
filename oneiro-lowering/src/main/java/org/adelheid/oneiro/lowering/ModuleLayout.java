package org.adelheid.oneiro.lowering;

import static org.adelheid.oneiro.lua.Expression.call;
import static org.adelheid.oneiro.lua.Expression.field;
import static org.adelheid.oneiro.lua.Expression.name;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.adelheid.oneiro.lua.Block;
import org.adelheid.oneiro.lua.Comment;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.Statement;
import org.adelheid.oneiro.lua.Trivia;
import org.jspecify.annotations.Nullable;

/**
 * A module's statements in the order Lua needs them. Java and Kotlin define every method before any code runs, while
 * Lua defines a function when its statement runs. So code that runs while the module loads comes after what it uses,
 * and a local that a function refers to before its own definition is declared ahead.
 */
public final class ModuleLayout {

	private ModuleLayout() {
	}

	/**
	 * The module's statements: the lines that load the modules it uses, the helpers, the module table, any locals
	 * declared ahead, the members, and {@code return Module}. The plugin class's {@code main.lua} has no table and
	 * returns nothing. A local declared ahead, {@code local name}, is assigned where its member's definition stands.
	 *
	 * @param moduleName the local that holds the module table
	 * @param table the module table's value, {@code {}}, or null for a chunk of locals such as {@code main.lua}
	 * @param instanceClass whether the table is a class whose objects look their methods up in it
	 * @param requires the local name of each required module and its require path, in order of first use
	 * @param helpers the helper functions the module uses
	 * @param language the language of the plugin, whose rules the helpers' comments name
	 * @param members the module's members, in source order
	 */
	public static <K> List<Block.Entry> assemble(String moduleName, @Nullable Expression table, boolean instanceClass,
			Map<String, String> requires, Collection<Helper> helpers, SourceLanguage language,
			List<? extends ModuleMember<K>> members) {
		List<ModuleMember<K>> ordered = order(members);
		Map<ModuleMember<K>, Integer> position = new HashMap<>();
		for (int index = 0; index < ordered.size(); index++) {
			position.put(ordered.get(index), index);
		}
		Map<K, ModuleMember<K>> byElement = byElement(members);
		Set<ModuleMember<K>> declaredAhead = new LinkedHashSet<>();
		for (ModuleMember<K> user : ordered) {
			for (K reference : user.references()) {
				ModuleMember<K> used = byElement.get(reference);
				if (used != null && used.localName() != null
						&& position.getOrDefault(used, -1) > position.getOrDefault(user, -1)) {
					declaredAhead.add(used);
				}
			}
		}

		List<Block.Entry> entries = new ArrayList<>();
		for (Map.Entry<String, String> require : requires.entrySet()) {
			Expression module = call(name("require"), Expression.string(require.getValue()));
			entries.add(Block.Entry.of(new Statement.Local(List.of(require.getKey()), List.of(module))));
		}
		for (Helper helper : helpers) {
			Trivia trivia = new Trivia(!entries.isEmpty(), helper.comment(language), null);
			entries.add(new Block.Entry(helper.definition(), trivia, null));
		}
		if (table != null) {
			entries.add(new Block.Entry(new Statement.Local(List.of(moduleName), List.of(table)),
					new Trivia(!entries.isEmpty(), List.of(), null), null));
		}
		if (instanceClass) {
			Expression moduleTable = name(moduleName);
			entries.add(Block.Entry.of(Statement.assignment(field(moduleTable, "__index"), moduleTable)));
		}
		if (!declaredAhead.isEmpty()) {
			List<String> locals = declaredAhead.stream().map(ModuleMember::localName).toList();
			Comment note = Comment.line("Declared ahead: used above their definitions below.");
			entries.add(new Block.Entry(new Statement.Local(locals, List.of()),
					new Trivia(!entries.isEmpty(), List.of(note), null), null));
		}
		boolean first = true;
		// A function ends with end, and a type's table opens the type: a blank line separates either from what follows
		boolean afterFunction = false;
		for (ModuleMember<K> member : ordered) {
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
				afterFunction = member.function() || member.type();
			}
		}
		if (table != null) {
			entries.add(new Block.Entry(new Statement.Return(List.of(name(moduleName))),
					new Trivia(true, List.of(), null), null));
		}
		return entries;
	}

	private static <K> Map<K, ModuleMember<K>> byElement(List<? extends ModuleMember<K>> members) {
		Map<K, ModuleMember<K>> byElement = new HashMap<>();
		members.forEach(member -> member.elements().forEach(element -> byElement.put(element, member)));
		return byElement;
	}

	/**
	 * Source order, except that a member comes after the class table its definition assigns into, and code that runs
	 * while the module loads (a field's value, an initializer) comes after everything it uses, including what the
	 * functions it calls use.
	 */
	private static <K> List<ModuleMember<K>> order(List<? extends ModuleMember<K>> members) {
		Placement<K> placement = new Placement<>(byElement(members));
		members.forEach(placement::place);
		return placement.ordered;
	}

	/** The members in the order {@link #order} gives them, built one placement at a time. */
	private static final class Placement<K> {

		private final Map<K, ModuleMember<K>> byElement;
		private final List<ModuleMember<K>> ordered = new ArrayList<>();
		private final Set<ModuleMember<K>> started = new HashSet<>();
		private final Set<ModuleMember<K>> usesStarted = new HashSet<>();

		Placement(Map<K, ModuleMember<K>> byElement) {
			this.byElement = byElement;
		}

		void place(ModuleMember<K> member) {
			// A member already placed, or one being placed further up a cycle, stays where it is
			if (!started.add(member)) {
				return;
			}
			for (K element : member.after()) {
				ModuleMember<K> needed = byElement.get(element);
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
		private void placeUses(ModuleMember<K> member) {
			if (!usesStarted.add(member)) {
				return;
			}
			for (K element : member.references()) {
				ModuleMember<K> used = byElement.get(element);
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
}
