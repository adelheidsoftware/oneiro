package org.adelheid.oneiro.lowering;

import static org.adelheid.oneiro.lua.Expression.binary;
import static org.adelheid.oneiro.lua.Expression.call;
import static org.adelheid.oneiro.lua.Expression.field;
import static org.adelheid.oneiro.lua.Expression.name;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.adelheid.oneiro.lua.BinaryOperator;
import org.adelheid.oneiro.lua.Expression;
import org.jspecify.annotations.Nullable;

/**
 * A literal format string, as Java's {@code String.format} and Kotlin's {@code format} take it, translated into one
 * call of the client's {@code formatStr}: {@code %d} and {@code %s} become {@code {}}, {@code %.2f} becomes
 * {@code {:.2f}}, and widths get their alignment written out, because the client aligns strings left and Java aligns
 * everything right. The client hands every number to {@code formatStr} as a double, so the integer options it cannot
 * print exactly, such as {@code %x}, are formatted by Lua's {@code string.format} inside the template.
 */
public final class FormatTranslation {

	private static final Pattern SPECIFIER = Pattern.compile("%(\\d+\\$)?([-#+ 0,(<]*)(\\d+)?(\\.\\d+)?([a-zA-Z%])");

	/** The kinds of value a format formats, which decide what its specifiers make of them. */
	public enum Kind { STRING, CHAR, INTEGER, LONG, FLOATING, BOOLEAN, OTHER }

	/** What the translation needs of the values the format formats. */
	public interface Values {

		int size();

		Kind kind(int index);

		/** The value as {@code formatStr} takes it: a {@code Long} through {@code tostring}, an object as its text. */
		Expression formatArgument(int index);

		/** The value as it is. */
		Expression expression(int index);
	}

	/**
	 * A format the client cannot print as the plugin's language does, or that the language itself rejects when it
	 * runs.
	 */
	public static final class Problem extends RuntimeException {

		private static final long serialVersionUID = 1L;

		private final int value;
		private final boolean notYet;
		private final String text;

		Problem(int value, boolean notYet, String text) {
			super(text, null, false, false);
			this.value = value;
			this.notYet = notYet;
			this.text = text;
		}

		/** The position of the value the problem concerns, or -1 for the format itself. */
		public int value() {
			return value;
		}

		/** Whether Oneiro could translate the format but does not yet, in which case the text names the construct. */
		public boolean notYet() {
			return notYet;
		}

		/** What is wrong, as an error states it. */
		public String text() {
			return text;
		}
	}

	private final Values values;
	/** The language whose format exceptions the messages name. */
	private final String language;

	private FormatTranslation(Values values, SourceLanguage language) {
		this.values = values;
		this.language = language == SourceLanguage.KOTLIN ? "Kotlin" : "Java";
	}

	/**
	 * The {@code formatStr} call for a format and its values.
	 *
	 * @throws Problem when the client cannot print the format as the language does
	 */
	public static Expression translate(String format, Values values, SourceLanguage language) {
		return new FormatTranslation(values, language).translate(format);
	}

	private Expression translate(String format) {
		boolean numbered = Pattern.compile("%\\d+\\$").matcher(format).find();
		StringBuilder template = new StringBuilder();
		List<Expression> formatArguments = new ArrayList<>();
		if (numbered) {
			for (int index = 0; index < values.size(); index++) {
				formatArguments.add(values.formatArgument(index));
			}
		}
		Matcher specifier = SPECIFIER.matcher(format);
		int textStart = 0;
		int ordinary = 0;
		while (specifier.find()) {
			template.append(escapeBraces(format.substring(textStart, specifier.start())));
			textStart = specifier.end();
			String conversion = specifier.group(5);
			if (conversion.equals("%")) {
				template.append('%');
				continue;
			}
			if (conversion.equals("n")) {
				template.append('\n');
				continue;
			}
			String explicit = specifier.group(1);
			int index = explicit != null
					? Integer.parseInt(explicit.substring(0, explicit.length() - 1)) - 1
					: ordinary++;
			if (index >= values.size()) {
				throw new Problem(-1, false, "This format has more specifiers than arguments, so " + language
						+ " would throw MissingFormatArgumentException.");
			}
			Placeholder placeholder = placeholder(conversion, nullToEmpty(specifier.group(2)), specifier.group(3),
					specifier.group(4), index, numbered);
			String position = numbered ? Integer.toString(index) : "";
			template.append('{').append(position).append(placeholder.options()).append('}');
			if (!numbered) {
				formatArguments.add(placeholder.argument());
			}
		}
		template.append(escapeBraces(format.substring(textStart)));
		List<Expression> callArguments = new ArrayList<>();
		callArguments.add(Expression.string(template.toString()));
		callArguments.addAll(formatArguments);
		return new Expression.Call(name("formatStr"), callArguments);
	}

	/** One translated specifier: the options after the colon, and the argument it formats. */
	private record Placeholder(String options, Expression argument) {
	}

	// The messages quote format specifiers, which Error Prone mistakes for unused format strings
	@SuppressWarnings("OrphanedFormatString")
	private Placeholder placeholder(String conversion, String flags, @Nullable String width, @Nullable String precision,
			int value, boolean numbered) {
		Kind kind = values.kind(value);
		if (flags.contains(",")) {
			throw new Problem(-1, false, "The client's formatStr adds no digit grouping, so %,d cannot print as "
					+ language + " prints it.");
		}
		return switch (conversion) {
			case "s" -> new Placeholder(colon(alignment(flags, width) + nullToEmpty(precision)),
					values.formatArgument(value));
			case "d" -> {
				requireIntegral(value, conversion);
				boolean signOrZero = flags.contains("+") || flags.contains(" ") || flags.contains("0");
				if (kind == Kind.LONG && signOrZero) {
					// The client would round a long to a double, so Lua formats it, flags and all
					yield new Placeholder("", luaFormat(flags, width, conversion, value, numbered));
				}
				yield new Placeholder(colon(numberOptions(flags, width, null, "")), values.formatArgument(value));
			}
			case "f", "e" -> {
				if (!isNumber(kind)) {
					throw new Problem(value, false, "%" + conversion + " needs a number, so " + language
							+ " would throw IllegalFormatConversionException.");
				}
				yield new Placeholder(colon(numberOptions(flags, width, precision, conversion)),
						values.expression(value));
			}
			case "x", "X", "o" -> {
				requireIntegral(value, conversion);
				yield new Placeholder("", luaFormat(flags, width, conversion, value, numbered));
			}
			case "c", "C" -> {
				if (numbered && (kind != Kind.CHAR || conversion.equals("C"))) {
					throw new Problem(-1, true, "Numbered format specifiers together with %" + conversion);
				}
				Expression character;
				if (kind == Kind.CHAR) {
					character = values.expression(value);
				} else if (kind == Kind.INTEGER) {
					character = call(field(name("string"), "char"), values.expression(value));
				} else {
					throw new Problem(value, false, "%c needs a char or an int, so " + language
							+ " would throw IllegalFormatConversionException.");
				}
				if (precision != null) {
					throw new Problem(-1, false, "%c takes no precision, so " + language
							+ " would throw IllegalFormatPrecisionException.");
				}
				yield new Placeholder(colon(alignment(flags, width)),
						conversion.equals("C") ? Expression.methodCall(character, "upper") : character);
			}
			case "b", "B" -> {
				if (kind != Kind.BOOLEAN) {
					throw new Problem(value, false, language + "'s %b of anything but a boolean prints whether it is"
							+ " null; pass that test, such as value != null, instead.");
				}
				if (precision != null || (numbered && conversion.equals("B"))) {
					throw new Problem(-1, true, "%" + conversion + " with a precision or a number");
				}
				Expression truth = conversion.equals("B")
						? Expression.methodCall(call(name("tostring"), values.expression(value)), "upper")
						: values.expression(value);
				yield new Placeholder(colon(alignment(flags, width)), truth);
			}
			case "g", "G" -> throw new Problem(-1, false, "The client's formatStr drops trailing zeros for %g, which "
					+ language + " keeps; use %f or %e.");
			case "t", "T" -> throw new Problem(-1, false, "The client has no os library to read the calendar from, so"
					+ " %t has nothing to format; format the parts yourself with %d.");
			default -> throw new Problem(-1, true, "The format specifier %" + conversion);
		};
	}

	private void requireIntegral(int value, String conversion) {
		Kind kind = values.kind(value);
		if (kind != Kind.INTEGER && kind != Kind.LONG) {
			throw new Problem(value, false, "%" + conversion + " needs an integer, so " + language
					+ " would throw IllegalFormatConversionException.");
		}
	}

	private static boolean isNumber(Kind kind) {
		return kind == Kind.INTEGER || kind == Kind.LONG || kind == Kind.FLOATING;
	}

	/**
	 * An integer formatted by Lua's {@code string.format}, for the options the client refuses because it sees every
	 * number as a double. An {@code int} is masked to 32 bits so a negative value prints as Java prints it.
	 */
	private Expression luaFormat(String flags, @Nullable String width, String conversion, int value,
			boolean numbered) {
		if (numbered) {
			throw new Problem(-1, true, "Numbered format specifiers together with %" + conversion);
		}
		Expression number = values.expression(value);
		if (values.kind(value) != Kind.LONG && !conversion.equals("d")) {
			number = binary(number, BinaryOperator.BIT_AND, new Expression.NumberLiteral("0xFFFFFFFF"));
		}
		String luaSpecifier = "%" + flags.replaceAll("[^-+ 0#]", "") + nullToEmpty(width) + conversion;
		return call(field(name("string"), "format"), Expression.string(luaSpecifier), number);
	}

	/** {@code :options}, or nothing when there are none. */
	private static String colon(String options) {
		return options.isEmpty() ? "" : ":" + options;
	}

	/** The alignment and width for fmtlib: {@code <8} for Java's {@code %-8s}, {@code >8} for {@code %8s}. */
	private static String alignment(String flags, @Nullable String width) {
		if (width == null) {
			return "";
		}
		return (flags.contains("-") ? "<" : ">") + width;
	}

	/** fmtlib's {@code [align][sign][0][width][.precision][type]} for a number. */
	private static String numberOptions(String flags, @Nullable String width, @Nullable String precision, String type) {
		boolean zeroPadded = flags.contains("0") && !flags.contains("-");
		String sign = flags.contains("+") ? "+" : flags.contains(" ") ? " " : "";
		String align = width != null && !zeroPadded ? (flags.contains("-") ? "<" : ">") : "";
		String size = width == null ? "" : (zeroPadded ? "0" : "") + width;
		return align + sign + size + nullToEmpty(precision) + type;
	}

	/** Text for a {@code formatStr} template, whose braces stand for themselves when doubled. */
	public static String escapeBraces(String text) {
		return text.replace("{", "{{").replace("}", "}}");
	}

	private static String nullToEmpty(@Nullable String text) {
		return text == null ? "" : text;
	}
}
