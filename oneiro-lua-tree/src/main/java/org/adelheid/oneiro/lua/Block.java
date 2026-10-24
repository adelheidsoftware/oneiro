package org.adelheid.oneiro.lua;

import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A sequence of statements: the body of a file, function, loop or branch.
 *
 * @param entries the statements, each with its comments and source position
 * @param closingComments comments after the last statement, printed just before the block's {@code end}
 */
public record Block(List<Entry> entries, List<Comment> closingComments) {

	public Block {
		entries = List.copyOf(entries);
		closingComments = List.copyOf(closingComments);
	}

	/** A block of statements without comments or source positions. */
	public static Block of(Statement... statements) {
		return new Block(Arrays.stream(statements).map(Entry::of).toList(), List.of());
	}

	/** Whether the block has neither statements nor comments, so that {@code function() end} fits on one line. */
	public boolean isEmpty() {
		return entries.isEmpty() && closingComments.isEmpty();
	}

	/**
	 * A statement as it sits in a block.
	 *
	 * @param trivia the blank line and comments around the statement
	 * @param origin the source line the statement came from, or null for a statement Oneiro adds itself
	 */
	public record Entry(Statement statement, Trivia trivia, @Nullable SourcePosition origin) {

		public static Entry of(Statement statement) {
			return new Entry(statement, Trivia.NONE, null);
		}
	}
}
