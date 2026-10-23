package plugin;

import java.util.HashMap;
import java.util.Map;

/** Letters and digits, one char at a time. */
public final class Letters {

	private Letters() {
	}

	/** The text with each lowercase letter moved along the alphabet, wrapping from z to a. */
	public static String caesar(String text, int shift) {
		StringBuilder shifted = new StringBuilder();
		for (char c : text.toCharArray()) {
			if (c >= 'a' && c <= 'z') {
				int index = (c - 'a' + shift) % 26;
				shifted.append((char) ('a' + index));
			} else {
				shifted.append(c);
			}
		}
		return shifted.toString();
	}

	/** The capital letters of a name, such as "SC" for "Session Clock". */
	public static String initials(String name) {
		String initials = "";
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (Character.isUpperCase(c)) {
				initials += c;
			}
		}
		return initials;
	}

	/** The sum of the digits in the text, skipping everything else. */
	public static int digitSum(String text) {
		int sum = 0;
		for (char c : text.toCharArray()) {
			if (Character.isDigit(c)) {
				sum += c - '0';
			}
		}
		return sum;
	}

	/** How often each letter comes up, ignoring case. */
	public static Map<Character, Integer> letterCounts(String text) {
		Map<Character, Integer> counts = new HashMap<>();
		for (char c : text.toLowerCase().toCharArray()) {
			if (Character.isLetter(c)) {
				counts.merge(c, 1, Integer::sum);
			}
		}
		return counts;
	}

	/** The letters from one to another, such as "abc" from 'a' to 'c'. */
	public static String alphabet(char from, char to) {
		StringBuilder letters = new StringBuilder();
		for (char c = from; c <= to; c++) {
			letters.append(c);
		}
		return letters.toString();
	}

	/** The number Java gives the char. */
	public static int code(char c) {
		return c;
	}

	/** The char with the number. */
	public static char fromCode(int code) {
		return (char) code;
	}

	/** The grade for a tier from 1 to 3. */
	public static char grade(int tier) {
		return switch (tier) {
			case 1 -> 'A';
			case 2 -> 'B';
			default -> 'F';
		};
	}

	/** The char with its number, such as "A=65". */
	public static String describe(char c) {
		return String.format("%c=%d", c, (int) c);
	}

	/** The word with its first letter capitalised. */
	public static String capitalised(String word) {
		return Character.toUpperCase(word.charAt(0)) + word.substring(1);
	}

	/** Where the char is first found in the text, or -1. */
	public static int find(String text, char sought) {
		return text.indexOf(sought);
	}

	/** The chars as text, a blank, and how many there are. */
	public static String joined(char[] chars) {
		char[] blanks = new char[1];
		blanks[0] = ' ';
		return new String(chars) + String.valueOf(blanks) + chars.length;
	}
}
