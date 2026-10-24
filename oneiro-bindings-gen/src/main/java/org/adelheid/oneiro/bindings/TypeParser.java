package org.adelheid.oneiro.bindings;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser for the LuaCATS type expressions in the stubs.
 *
 * <p>A type is followed by free text on the same line ({@code ---@field x Int The world tile X}), so the parser reads
 * one type from the start of the text and reports where it stopped. Whitespace is significant only at the top level:
 * inside {@code fun(...)}, {@code <...>} and {@code {...}} it is skipped, outside it ends the type.
 */
final class TypeParser {

	/** A parsed type and the index just after it in the source text. */
	record Result(TypeRef type, int end) {
	}

	/** Thrown when the text does not start with a type this parser understands. */
	static final class ParseException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		ParseException(String message) {
			super(message);
		}
	}

	private final String text;
	private int pos;
	private int depth;

	private TypeParser(String text, int start) {
		this.text = text;
		this.pos = start;
	}

	/** Parses the type that starts at {@code start} in {@code text}. */
	static Result parsePrefix(String text, int start) {
		TypeParser parser = new TypeParser(text, start);
		TypeRef type = parser.union();
		return new Result(type, parser.pos);
	}

	/** Parses a whole text as one type. */
	static TypeRef parse(String text) {
		Result result = parsePrefix(text.strip(), 0);
		if (result.end() != text.strip().length()) {
			throw new ParseException("unexpected text after the type: '" + text.strip().substring(result.end()) + "'");
		}
		return result.type();
	}

	private TypeRef union() {
		List<TypeRef> options = new ArrayList<>();
		options.add(postfix());
		while (peekSymbol('|')) {
			pos++;
			options.add(postfix());
		}
		return options.size() == 1 ? options.getFirst() : new TypeRef.Union(options);
	}

	private TypeRef postfix() {
		TypeRef type = primary();
		while (true) {
			if (text.startsWith("[]", pos)) {
				pos += 2;
				type = new TypeRef.ArrayOf(type);
			} else if (pos < text.length() && text.charAt(pos) == '?') {
				pos++;
				type = new TypeRef.Optional(type);
			} else {
				return type;
			}
		}
	}

	private TypeRef primary() {
		skipInnerWhitespace();
		if (pos >= text.length()) {
			throw new ParseException("type expected at end of text");
		}
		char c = text.charAt(pos);
		if (text.startsWith("fun(", pos)) {
			return function();
		}
		if (c == '{') {
			return table();
		}
		if (c == '(') {
			pos++;
			depth++;
			TypeRef inner = union();
			expect(')');
			depth--;
			return inner;
		}
		if (c == '"') {
			int close = text.indexOf('"', pos + 1);
			if (close < 0) {
				throw new ParseException("unterminated string literal type");
			}
			String literal = text.substring(pos + 1, close);
			pos = close + 1;
			return new TypeRef.Literal(literal);
		}
		String name = name();
		List<TypeRef> args = new ArrayList<>();
		if (pos < text.length() && text.charAt(pos) == '<') {
			pos++;
			depth++;
			args.add(union());
			while (peekSymbol(',')) {
				pos++;
				args.add(union());
			}
			expect('>');
			depth--;
		}
		return new TypeRef.Named(name, args);
	}

	private TypeRef function() {
		pos += "fun(".length();
		depth++;
		List<TypeRef.Param> params = new ArrayList<>();
		if (!peekSymbol(')')) {
			do {
				if (peekSymbol(',')) {
					pos++;
				}
				params.add(param());
			} while (peekSymbol(','));
		}
		expect(')');
		depth--;
		// A return type follows "):" directly. Only one is read: in "fun(cb: fun(e: T): R, other: X)" a comma after R
		// belongs to the enclosing parameter list, and the stubs never declare multiple returns in a fun type.
		List<TypeRef> returns = new ArrayList<>();
		if (peekReturnColon()) {
			pos++;
			depth++;
			returns.add(union());
			depth--;
		}
		return new TypeRef.Function(params, returns);
	}

	/** Whether a {@code :} introduces the function type's return, directly or after spaces inside brackets. */
	private boolean peekReturnColon() {
		int save = pos;
		skipInnerWhitespace();
		boolean colon = pos < text.length() && text.charAt(pos) == ':';
		if (!colon) {
			pos = save;
		}
		return colon;
	}

	private TypeRef.Param param() {
		skipInnerWhitespace();
		int save = pos;
		if (text.startsWith("...", pos)) {
			pos += 3;
			if (peekSymbol(':')) {
				pos++;
				return new TypeRef.Param("...", union());
			}
			return new TypeRef.Param("...", TypeRef.Named.of("any"));
		}
		String name = name();
		if (peekSymbol(':')) {
			pos++;
			return new TypeRef.Param(name, union());
		}
		pos = save;
		return new TypeRef.Param("", union());
	}

	private TypeRef table() {
		pos++;
		depth++;
		List<TypeRef.Entry> entries = new ArrayList<>();
		while (!peekSymbol('}')) {
			if (peekSymbol('[')) {
				pos++;
				skipInnerWhitespace();
				if (pos < text.length() && Character.isDigit(text.charAt(pos))) {
					// {[1]: Int, [2]: ObjType} is a tuple: the keys are positions, not a key type
					int start = pos;
					while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
						pos++;
					}
					String position = text.substring(start, pos);
					expect(']');
					expect(':');
					entries.add(new TypeRef.Entry("[" + position + "]", null, union()));
				} else {
					TypeRef keyType = union();
					expect(']');
					expect(':');
					entries.add(new TypeRef.Entry("", keyType, union()));
				}
			} else {
				skipInnerWhitespace();
				String name = name();
				expect(':');
				entries.add(new TypeRef.Entry(name, null, union()));
			}
			if (peekSymbol(',')) {
				pos++;
			}
		}
		expect('}');
		depth--;
		return new TypeRef.Table(entries);
	}

	private String name() {
		int start = pos;
		while (pos < text.length()) {
			char c = text.charAt(pos);
			if (Character.isLetterOrDigit(c) || c == '_' || c == '.') {
				pos++;
			} else {
				break;
			}
		}
		if (pos == start) {
			throw new ParseException("name expected at '" + text.substring(start) + "'");
		}
		return text.substring(start, pos);
	}

	/** Whether the next non-space character (spaces skipped only inside brackets) is {@code symbol}. */
	private boolean peekSymbol(char symbol) {
		skipInnerWhitespace();
		return pos < text.length() && text.charAt(pos) == symbol;
	}

	private void expect(char symbol) {
		if (!peekSymbol(symbol)) {
			String rest = text.substring(Math.min(pos, text.length()));
			throw new ParseException("'" + symbol + "' expected at '" + rest + "'");
		}
		pos++;
	}

	private void skipInnerWhitespace() {
		if (depth == 0) {
			return;
		}
		while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
			pos++;
		}
	}
}
