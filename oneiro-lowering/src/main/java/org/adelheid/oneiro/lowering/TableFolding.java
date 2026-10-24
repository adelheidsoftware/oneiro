package org.adelheid.oneiro.lowering;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.adelheid.oneiro.lua.Block;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.NameUses;
import org.adelheid.oneiro.lua.Statement;
import org.adelheid.oneiro.lua.Trivia;

/**
 * Folds {@code local t = {}} and the field assignments right after it into one constructor,
 * {@code local t = { a = x, b = y }}, the way a table the plugin fills in for the API reads in Lua.
 */
public final class TableFolding {

	private TableFolding() {
	}

	/**
	 * A block's entries with each empty table's field assignments folded into it. Only field assignments right after
	 * it fold, without comments or blank lines between them, each field once, and only values that do not mention the
	 * table, which a constructor could not see yet.
	 */
	public static List<Block.Entry> fold(List<Block.Entry> entries) {
		List<Block.Entry> folded = new ArrayList<>();
		for (int index = 0; index < entries.size(); index++) {
			Block.Entry entry = entries.get(index);
			if (!(entry.statement() instanceof Statement.Local local) || local.names().size() != 1
					|| local.values().size() != 1 || !(local.values().getFirst() instanceof Expression.Table empty)
					|| !empty.fields().isEmpty() || entry.trivia().trailing() != null) {
				folded.add(entry);
				continue;
			}
			String table = local.names().getFirst();
			List<Expression.TableField> fields = new ArrayList<>();
			Set<String> keys = new HashSet<>();
			int next = index + 1;
			for (; next < entries.size(); next++) {
				Block.Entry candidate = entries.get(next);
				Trivia trivia = candidate.trivia();
				if (!trivia.leading().isEmpty() || trivia.blankLineBefore() || trivia.trailing() != null
						|| !(candidate.statement() instanceof Statement.Assignment assignment)
						|| assignment.targets().size() != 1 || assignment.values().size() != 1
						|| !(assignment.targets().getFirst() instanceof Expression.Field field)
						|| !(field.target() instanceof Expression.Name target) || !target.name().equals(table)
						|| NameUses.mentions(assignment.values().getFirst(), table) || !keys.add(field.name())) {
					break;
				}
				fields.add(new Expression.TableField.Named(field.name(), assignment.values().getFirst()));
			}
			if (fields.isEmpty()) {
				folded.add(entry);
				continue;
			}
			folded.add(new Block.Entry(new Statement.Local(local.names(), List.of(new Expression.Table(fields))),
					entry.trivia(), entry.origin()));
			index = next - 1;
		}
		return folded;
	}
}
