package org.adelheid.oneiro.lua;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Lays a {@link Doc} out within a line width, using Wadler's algorithm as Prettier implements it: a group is flat when
 * its content, followed by whatever comes after it up to the next line break, fits in the rest of the line.
 */
final class DocRenderer {

	static final String INDENT = "    ";

	private final int width;

	DocRenderer(int width) {
		this.width = width;
	}

	/**
	 * The laid-out text and, for each of its lines, the source position it came from.
	 *
	 * @param lineOrigins one entry per line of {@code text}; null for blank lines and lines without a source
	 */
	record Rendered(String text, List<@Nullable SourcePosition> lineOrigins) {
	}

	/** Pending work: a document, the indentation its line breaks use, and whether it is laid out flat. */
	private record Command(int indent, boolean flat, Doc doc) {
	}

	Rendered render(Doc doc) {
		Output output = new Output();
		Deque<Command> stack = new ArrayDeque<>();
		stack.push(new Command(0, false, doc));
		while (!stack.isEmpty()) {
			Command command = stack.pop();
			switch (command.doc()) {
				case Doc.Text text -> output.write(text.text());
				case Doc.Verbatim verbatim -> output.writeVerbatim(verbatim.text());
				case Doc.SoftLine line -> {
					if (command.flat()) {
						output.write(line.flat());
					} else {
						output.newline(command.indent());
					}
				}
				case Doc.HardLine _ -> output.newline(command.indent());
				case Doc.FlatOrBroken choice -> output.write(command.flat() ? choice.flat() : choice.broken());
				case Doc.Indent indent -> stack.push(new Command(command.indent() + 1, command.flat(), indent.content()));
				case Doc.Group group -> {
					boolean flat = command.flat()
							|| fits(new Command(command.indent(), true, group.content()), stack, output.column());
					stack.push(new Command(command.indent(), flat, group.content()));
				}
				case Doc.Concat concat -> pushParts(stack, command, concat.parts());
				case Doc.Hang hang -> hang(command, hang, stack, output);
				case Doc.Hug hug -> {
					boolean hugged = firstLineFits(new Command(command.indent(), false, hug.hugged()), output.column());
					stack.push(new Command(command.indent(), command.flat(), hugged ? hug.hugged() : hug.expanded()));
				}
				case Doc.Mark mark -> output.mark(mark.origin());
			}
		}
		return output.finish();
	}

	/** Lays out a {@link Doc.Hang}: on one line; else the body alone on the next line; else the body broken in place. */
	private void hang(Command command, Doc.Hang hang, Deque<Command> stack, Output output) {
		Doc oneLine = oneLine(hang);
		if (command.flat() || fits(new Command(command.indent(), true, oneLine), stack, output.column())) {
			stack.push(new Command(command.indent(), true, oneLine));
			return;
		}
		int deeper = command.indent() + 1;
		if (fits(new Command(deeper, true, hang.body()), stack, deeper * INDENT.length())) {
			stack.push(new Command(deeper, true, hang.body()));
			stack.push(new Command(deeper, false, Doc.HARD_LINE));
			stack.push(new Command(command.indent(), false, hang.head()));
			return;
		}
		stack.push(new Command(command.indent(), false, oneLine));
	}

	/**
	 * Whether {@code next} fits in the line from {@code column} on, together with what follows it on the stack up to
	 * the next line break.
	 */
	private boolean fits(Command next, Deque<Command> rest, int column) {
		int remaining = width - column;
		Deque<Command> work = new ArrayDeque<>();
		work.push(next);
		Iterator<Command> following = rest.iterator();
		while (remaining >= 0) {
			Command command;
			if (!work.isEmpty()) {
				command = work.pop();
			} else if (following.hasNext()) {
				command = following.next();
			} else {
				return true;
			}
			switch (command.doc()) {
				case Doc.Text text -> remaining -= width(text.text());
				case Doc.Verbatim verbatim -> {
					int lineBreak = verbatim.text().indexOf('\n');
					if (lineBreak < 0) {
						remaining -= width(verbatim.text());
					} else {
						// Like a hard line: it cannot be flat, and after it the line ends
						return !command.flat() && width(verbatim.text().substring(0, lineBreak)) <= remaining;
					}
				}
				case Doc.SoftLine line -> {
					if (!command.flat()) {
						return true;
					}
					remaining -= width(line.flat());
				}
				// Inside the measured content a hard line means it cannot be flat; after it, the line ends there
				case Doc.HardLine _ -> {
					return !command.flat();
				}
				case Doc.FlatOrBroken choice -> remaining -= width(command.flat() ? choice.flat() : choice.broken());
				case Doc.Indent indent -> work.push(new Command(command.indent() + 1, command.flat(), indent.content()));
				case Doc.Group group -> work.push(new Command(command.indent(), command.flat(), group.content()));
				case Doc.Concat concat -> pushParts(work, command, concat.parts());
				case Doc.Hang hang -> work.push(new Command(command.indent(), command.flat(), oneLine(hang)));
				case Doc.Hug hug -> work.push(new Command(command.indent(), command.flat(), hug.hugged()));
				case Doc.Mark _ -> {
				}
			}
		}
		return false;
	}

	/**
	 * Whether {@code next} fits in the line from {@code column} on, up to its first line break, with each group in it
	 * flat unless it holds a line break no layout can avoid, as the renderer will lay it out.
	 */
	private boolean firstLineFits(Command next, int column) {
		int remaining = width - column;
		Deque<Command> work = new ArrayDeque<>();
		work.push(next);
		while (remaining >= 0 && !work.isEmpty()) {
			Command command = work.pop();
			switch (command.doc()) {
				case Doc.Text text -> remaining -= width(text.text());
				case Doc.Verbatim verbatim -> {
					int lineBreak = verbatim.text().indexOf('\n');
					if (lineBreak >= 0) {
						return width(verbatim.text().substring(0, lineBreak)) <= remaining;
					}
					remaining -= width(verbatim.text());
				}
				case Doc.SoftLine line -> {
					if (!command.flat()) {
						return true;
					}
					remaining -= width(line.flat());
				}
				case Doc.HardLine _ -> {
					return true;
				}
				case Doc.FlatOrBroken choice -> remaining -= width(command.flat() ? choice.flat() : choice.broken());
				case Doc.Indent indent -> work.push(new Command(command.indent() + 1, command.flat(), indent.content()));
				case Doc.Group group -> work.push(new Command(command.indent(), !Doc.breaks(group), group.content()));
				case Doc.Concat concat -> pushParts(work, command, concat.parts());
				case Doc.Hang hang -> work.push(new Command(command.indent(), !Doc.breaks(hang), oneLine(hang)));
				case Doc.Hug hug -> work.push(new Command(command.indent(), false, hug.hugged()));
				case Doc.Mark _ -> {
				}
			}
		}
		return remaining >= 0;
	}

	private static Doc oneLine(Doc.Hang hang) {
		return Doc.concat(hang.head(), Doc.text(" "), hang.body());
	}

	/** Pushes the parts so that the first is popped first, each with the command's indentation and layout. */
	private static void pushParts(Deque<Command> stack, Command command, List<Doc> parts) {
		for (int index = parts.size() - 1; index >= 0; index--) {
			stack.push(new Command(command.indent(), command.flat(), parts.get(index)));
		}
	}

	private static int width(String text) {
		return text.codePointCount(0, text.length());
	}

	/** The text being built, with the current column and the source position of each line. */
	private static final class Output {

		private final StringBuilder text = new StringBuilder();
		private final List<@Nullable SourcePosition> lineOrigins = new ArrayList<>();
		private @Nullable SourcePosition origin;
		private int column;
		// The indentation of the current line, written together with its first text so that blank lines stay empty
		private int pendingIndent;
		private boolean lineStarted;

		Output() {
			lineOrigins.add(null);
		}

		/** The column the next text starts at, counting the indentation still to be written. */
		int column() {
			return lineStarted ? column : pendingIndent * INDENT.length();
		}

		void write(String part) {
			if (part.isEmpty()) {
				return;
			}
			if (!lineStarted) {
				text.append(INDENT.repeat(pendingIndent));
				column = pendingIndent * INDENT.length();
				lineStarted = true;
				lineOrigins.set(lineOrigins.size() - 1, origin);
			}
			text.append(part);
			column += width(part);
		}

		void writeVerbatim(String part) {
			String[] lines = part.split("\n", -1);
			write(lines[0]);
			for (int index = 1; index < lines.length; index++) {
				text.append('\n');
				lineOrigins.add(origin);
				lineStarted = true;
				column = 0;
				text.append(lines[index]);
				column += width(lines[index]);
			}
		}

		void newline(int indent) {
			text.append('\n');
			lineOrigins.add(null);
			pendingIndent = indent;
			lineStarted = false;
			column = 0;
		}

		void mark(@Nullable SourcePosition position) {
			origin = position;
		}

		Rendered finish() {
			// The text ends with a line break, after which no further line starts
			if (!lineStarted && lineOrigins.size() > 1) {
				lineOrigins.removeLast();
			}
			return new Rendered(text.toString(), Collections.unmodifiableList(lineOrigins));
		}
	}
}
