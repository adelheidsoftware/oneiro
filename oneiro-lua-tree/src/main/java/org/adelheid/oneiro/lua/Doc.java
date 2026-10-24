package org.adelheid.oneiro.lua;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A layout document for the printer, after Philip Wadler's "A prettier printer": text, line breaks that a group may
 * keep on one line, indentation and groups. {@link DocRenderer} lays a group out flat when it fits in the line and
 * holds no hard line; otherwise every soft line directly in it breaks.
 */
sealed interface Doc {

	Doc HARD_LINE = new HardLine();
	Doc SPACE_OR_LINE = new SoftLine(" ");
	Doc NOTHING_OR_LINE = new SoftLine("");

	/** Text on one line. */
	record Text(String text) implements Doc {

		public Text {
			if (text.indexOf('\n') >= 0) {
				throw new IllegalArgumentException("Text is a single line; use a line break: " + text);
			}
		}
	}

	/** Text written exactly as given, line breaks included, with no indentation after them: a long string's lines. */
	record Verbatim(String text) implements Doc {
	}

	/** A line break that prints as {@code flat} when its group fits on one line. */
	record SoftLine(String flat) implements Doc {
	}

	/** A line break in every layout; no group around it can be flat. */
	record HardLine() implements Doc {
	}

	/** Text that differs between layouts, such as the trailing comma a table gets only when it is broken. */
	record FlatOrBroken(String flat, String broken) implements Doc {
	}

	/** Content whose line breaks indent one level deeper. */
	record Indent(Doc content) implements Doc {
	}

	/** Content laid out flat or broken as a whole. */
	record Group(Doc content) implements Doc {
	}

	record Concat(List<Doc> parts) implements Doc {

		public Concat {
			parts = List.copyOf(parts);
		}
	}

	/**
	 * StyLua's layout for the right side of an assignment: {@code head body} on one line if it fits; otherwise the
	 * body alone on the next line, one level deeper, if it fits there; otherwise {@code head body} with the body
	 * broken.
	 */
	record Hang(Doc head, Doc body) implements Doc {
	}

	/**
	 * StyLua's layout for a call with an argument that cannot be on one line, such as a function with a body. When the
	 * line up to the first break fits, the call is {@code hugged}: every argument stays on the call's line and that
	 * argument breaks inside. Otherwise it is {@code expanded}, one argument per line.
	 */
	record Hug(Doc hugged, Doc expanded) implements Doc {
	}

	/** Takes no space; the lines printed after it come from {@code origin}, or from no source line when null. */
	record Mark(@Nullable SourcePosition origin) implements Doc {
	}

	static Doc text(String text) {
		return new Text(text);
	}

	static Doc concat(Doc... parts) {
		return new Concat(List.of(parts));
	}

	static Doc concat(List<Doc> parts) {
		return new Concat(parts);
	}

	static Doc group(Doc... parts) {
		return new Group(concat(parts));
	}

	static Doc indent(Doc... parts) {
		return new Indent(concat(parts));
	}

	/** Whether the document holds a line break that no layout can avoid. */
	static boolean breaks(Doc doc) {
		return switch (doc) {
			case HardLine _ -> true;
			case Verbatim verbatim -> verbatim.text().indexOf('\n') >= 0;
			case Text _, SoftLine _, FlatOrBroken _, Mark _ -> false;
			case Indent indent -> breaks(indent.content());
			case Group group -> breaks(group.content());
			case Concat concat -> concat.parts().stream().anyMatch(Doc::breaks);
			case Hang hang -> breaks(hang.head()) || breaks(hang.body());
			case Hug hug -> breaks(hug.hugged());
		};
	}
}
