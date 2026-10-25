package org.adelheid.oneiro.bindings;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Splits Lua 5.4 source into tokens for {@link LuaSyntax}, comments included, because LuaCATS annotations are comments.
 * Each token keeps its line, and its text as written; a string also keeps its value, with the escapes read.
 */
final class LuaLexer {

	/** What a token is. */
	enum Kind {
		NAME,
		KEYWORD,
		NUMBER,
		STRING,
		SYMBOL,
		/** A {@code --} comment to the end of its line. */
		COMMENT,
		/** A {@code --[[ ... ]]} comment, which can span lines. */
		LONG_COMMENT,
		END,
	}

	/**
	 * One token.
	 *
	 * @param text the source text; for a comment, what follows its {@code --}, without trailing whitespace
	 * @param value a string's value; otherwise the text
	 * @param line the line the token starts on, counted from 1
	 * @param endLine the line it ends on, which differs from {@code line} only for long strings and comments
	 * @param trailing for a comment, whether code comes before it on its line
	 */
	record Token(Kind kind, String text, String value, int line, int endLine, boolean trailing) {

		boolean is(String symbolOrKeyword) {
			return (kind == Kind.SYMBOL || kind == Kind.KEYWORD) && text.equals(symbolOrKeyword);
		}
	}

	private static final Set<String> KEYWORDS = Set.of("and", "break", "do", "else", "elseif", "end", "false", "for",
			"function", "goto", "if", "in", "local", "nil", "not", "or", "repeat", "return", "then", "true", "until",
			"while");
	/** The symbols of three and two characters, longest first, as Lua reads them. */
	private static final List<String> LONG_SYMBOLS = List.of("...", "..", "==", "~=", "<=", ">=", "<<", ">>", "//",
			"::");
	private static final String SHORT_SYMBOLS = "+-*/%^#&~|<>=(){}[];:,.";

	private final String source;
	private final List<Token> tokens = new ArrayList<>();
	private int position;
	private int line = 1;
	/** The line the last token that is not a comment ended on, which makes a comment on it trailing. */
	private int codeLine;

	private LuaLexer(String source) {
		this.source = source;
	}

	/** The tokens of {@code source}, ending with an {@link Kind#END} token. */
	static List<Token> tokenize(String source) {
		LuaLexer lexer = new LuaLexer(source);
		lexer.run();
		return lexer.tokens;
	}

	private void run() {
		// Lua skips a first line that starts with #, such as a Unix shebang
		if (source.startsWith("#")) {
			while (position < source.length() && !isNewline(source.charAt(position))) {
				position++;
			}
		}
		while (true) {
			skipWhitespace();
			if (position >= source.length()) {
				tokens.add(new Token(Kind.END, "", "", line, line, false));
				return;
			}
			char c = source.charAt(position);
			int start = position;
			int startLine = line;
			if (source.startsWith("--", position)) {
				comment(startLine);
			} else if (isNameChar(c) && !isDigit(c)) {
				while (position < source.length() && isNameChar(source.charAt(position))) {
					position++;
				}
				String name = source.substring(start, position);
				code(KEYWORDS.contains(name) ? Kind.KEYWORD : Kind.NAME, name, name, startLine);
			} else if (isDigit(c)
					|| (c == '.' && position + 1 < source.length() && isDigit(source.charAt(position + 1)))) {
				number(startLine);
			} else if (c == '"' || c == '\'') {
				String value = shortString(c);
				code(Kind.STRING, source.substring(start, position), value, startLine);
			} else if (c == '[' && longBracketLevel() >= 0) {
				String value = longBracket();
				code(Kind.STRING, source.substring(start, position), value, startLine);
			} else {
				symbol(startLine);
			}
		}
	}

	private void code(Kind kind, String text, String value, int startLine) {
		tokens.add(new Token(kind, text, value, startLine, line, false));
		codeLine = line;
	}

	private void comment(int startLine) {
		boolean trailing = codeLine == startLine && !tokens.isEmpty();
		position += 2;
		if (position < source.length() && source.charAt(position) == '[' && longBracketLevel() >= 0) {
			String text = longBracket();
			tokens.add(new Token(Kind.LONG_COMMENT, text, text, startLine, line, trailing));
			return;
		}
		int start = position;
		while (position < source.length() && !isNewline(source.charAt(position))) {
			position++;
		}
		String text = source.substring(start, position).stripTrailing();
		tokens.add(new Token(Kind.COMMENT, text, text, startLine, startLine, trailing));
	}

	/** A numeral, read as Lua reads one: hexadecimal digits, dots and exponents, then no letter. */
	private void number(int startLine) {
		int start = position;
		String exponent = source.startsWith("0x", position) || source.startsWith("0X", position) ? "Pp" : "Ee";
		if (exponent.equals("Pp")) {
			position += 2;
		}
		while (position < source.length()) {
			char c = source.charAt(position);
			if (exponent.indexOf(c) >= 0) {
				position++;
				if (position < source.length() && "+-".indexOf(source.charAt(position)) >= 0) {
					position++;
				}
			} else if (isHexDigit(c) || c == '.') {
				position++;
			} else {
				break;
			}
		}
		if (position < source.length() && isNameChar(source.charAt(position))) {
			throw new LuaSyntax.SyntaxError(line, "malformed number near '"
					+ source.substring(start, position + 1) + "'");
		}
		String text = source.substring(start, position);
		code(Kind.NUMBER, text, text, startLine);
	}

	private String shortString(char quote) {
		StringBuilder value = new StringBuilder();
		position++;
		while (true) {
			if (position >= source.length() || isNewline(source.charAt(position))) {
				throw new LuaSyntax.SyntaxError(line, "unfinished string");
			}
			char c = source.charAt(position);
			if (c == quote) {
				position++;
				return value.toString();
			}
			if (c != '\\') {
				value.append(c);
				position++;
				continue;
			}
			position++;
			if (position >= source.length()) {
				throw new LuaSyntax.SyntaxError(line, "unfinished string");
			}
			char escape = source.charAt(position);
			switch (escape) {
				case 'a' -> value.append('\u0007');
				case 'b' -> value.append('\b');
				case 'f' -> value.append('\f');
				case 'n' -> value.append('\n');
				case 'r' -> value.append('\r');
				case 't' -> value.append('\t');
				case 'v' -> value.append('\u000B');
				case '\\', '"', '\'' -> value.append(escape);
				case '\n', '\r' -> {
					newline();
					value.append('\n');
					continue;
				}
				case 'x' -> {
					value.append((char) Integer.parseInt(hexDigits(position + 1, 2), 16));
					position += 2;
				}
				case 'z' -> {
					position++;
					skipWhitespace();
					continue;
				}
				case 'u' -> {
					int close = source.indexOf('}', position);
					if (position + 1 >= source.length() || source.charAt(position + 1) != '{' || close < 0) {
						throw new LuaSyntax.SyntaxError(line, "invalid escape sequence");
					}
					value.appendCodePoint(Integer.parseInt(source.substring(position + 2, close), 16));
					position = close;
				}
				default -> {
					if (!isDigit(escape)) {
						throw new LuaSyntax.SyntaxError(line, "invalid escape sequence '\\" + escape + "'");
					}
					int end = position;
					while (end < source.length() && end < position + 3 && isDigit(source.charAt(end))) {
						end++;
					}
					value.append((char) Integer.parseInt(source.substring(position, end)));
					position = end - 1;
				}
			}
			position++;
		}
	}

	/** {@code count} hexadecimal digits from {@code from}, or a syntax error. */
	private String hexDigits(int from, int count) {
		if (from + count > source.length()) {
			throw new LuaSyntax.SyntaxError(line, "invalid escape sequence");
		}
		String digits = source.substring(from, from + count);
		if (!digits.chars().allMatch(c -> isHexDigit((char) c))) {
			throw new LuaSyntax.SyntaxError(line, "invalid escape sequence");
		}
		return digits;
	}

	/** The level of the long bracket at the position, {@code [==[} being 2, or -1 when there is none. */
	private int longBracketLevel() {
		int at = position + 1;
		while (at < source.length() && source.charAt(at) == '=') {
			at++;
		}
		return at < source.length() && source.charAt(at) == '[' ? at - position - 1 : -1;
	}

	/** The text inside the long bracket at the position, which Lua starts after a newline that follows the opening. */
	private String longBracket() {
		int level = longBracketLevel();
		position += level + 2;
		if (position < source.length() && isNewline(source.charAt(position))) {
			newline();
		}
		String close = "]" + "=".repeat(level) + "]";
		StringBuilder text = new StringBuilder();
		while (!source.startsWith(close, position)) {
			if (position >= source.length()) {
				throw new LuaSyntax.SyntaxError(line, "unfinished long string or comment");
			}
			char c = source.charAt(position);
			if (isNewline(c)) {
				newline();
				text.append('\n');
			} else {
				text.append(c);
				position++;
			}
		}
		position += close.length();
		return text.toString();
	}

	private void symbol(int startLine) {
		for (String symbol : LONG_SYMBOLS) {
			if (source.startsWith(symbol, position)) {
				position += symbol.length();
				code(Kind.SYMBOL, symbol, symbol, startLine);
				return;
			}
		}
		char c = source.charAt(position);
		if (SHORT_SYMBOLS.indexOf(c) < 0) {
			throw new LuaSyntax.SyntaxError(line, "unexpected symbol '" + c + "'");
		}
		position++;
		String symbol = String.valueOf(c);
		code(Kind.SYMBOL, symbol, symbol, startLine);
	}

	private void skipWhitespace() {
		while (position < source.length()) {
			char c = source.charAt(position);
			if (isNewline(c)) {
				newline();
			} else if (c == ' ' || c == '\t' || c == '\f' || c == '\u000B') {
				position++;
			} else {
				return;
			}
		}
	}

	/** Consumes one line break, which Lua counts once whether it is \n, \r, \r\n or \n\r. */
	private void newline() {
		char first = source.charAt(position);
		position++;
		if (position < source.length()) {
			char second = source.charAt(position);
			if (isNewline(second) && second != first) {
				position++;
			}
		}
		line++;
	}

	private static boolean isNewline(char c) {
		return c == '\n' || c == '\r';
	}

	private static boolean isNameChar(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || isDigit(c) || c == '_';
	}

	private static boolean isDigit(char c) {
		return c >= '0' && c <= '9';
	}

	private static boolean isHexDigit(char c) {
		return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
	}
}
