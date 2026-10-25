package org.adelheid.oneiro.lowering;

import java.util.List;
import java.util.Set;
import org.adelheid.oneiro.lua.Block;
import org.jspecify.annotations.Nullable;

/**
 * One piece of a module: its statements, with the comments and blank line before them; for a local, the form that
 * assigns it when it was declared ahead; and what decides its place. Each front end keys members by its own
 * declarations.
 *
 * @param <K> a front end's declarations: javac's elements, or Kotlin's declarations
 */
public interface ModuleMember<K> {

	/** The declarations it stands for, by which other members refer to it. */
	List<K> elements();

	/** Whether the member defines a function, whose body only runs when called. */
	boolean function();

	/**
	 * Whether the member is a type's table, {@code local Cell = {}} and the lines after it, which a blank line
	 * separates from what follows, as it does a function.
	 */
	default boolean type() {
		return false;
	}

	/** The Lua local the member defines, or null for a table field or loose statements. */
	@Nullable String localName();

	/** The member's statements, with their comments. */
	List<Block.Entry> entries();

	/** The member's statements when its local was declared ahead. */
	List<Block.Entry> assignForm();

	/** The declarations of the file that the member's code uses. */
	Set<K> references();

	/** The declarations whose members must come first, such as the table of the member's own class. */
	Set<K> after();
}
