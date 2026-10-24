package org.adelheid.oneiro.plugin;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** The few pieces of JSON {@code plugin.json} needs, written the way its official example lays them out. */
final class Json {

	private Json() {
	}

	/** A JSON string, with the characters JSON requires escaped. */
	static String string(String text) {
		StringBuilder escaped = new StringBuilder("\"");
		for (int index = 0; index < text.length(); index++) {
			char c = text.charAt(index);
			switch (c) {
				case '"' -> escaped.append("\\\"");
				case '\\' -> escaped.append("\\\\");
				case '\n' -> escaped.append("\\n");
				case '\r' -> escaped.append("\\r");
				case '\t' -> escaped.append("\\t");
				default -> {
					if (c < 0x20) {
						escaped.append(String.format("\\u%04x", (int) c));
					} else {
						escaped.append(c);
					}
				}
			}
		}
		return escaped.append('"').toString();
	}

	/** A list of strings on one line: {@code []}, or {@code [ "a", "b" ]}. */
	static String list(List<String> values) {
		return values.isEmpty()
				? "[]"
				: "[ " + values.stream().map(Json::string).collect(Collectors.joining(", ")) + " ]";
	}

	/** An object of values already written as JSON, on one line: {@code {}}, or {@code { "a": 1 }}. */
	static String object(Map<String, String> values) {
		return values.isEmpty()
				? "{}"
				: "{ " + values.entrySet().stream()
						.map(entry -> string(entry.getKey()) + ": " + entry.getValue())
						.collect(Collectors.joining(", ")) + " }";
	}
}
