package org.adelheid.oneiro.lowering.client;

import org.adelheid.oneiro.lowering.SourceLanguage;

/**
 * The areas of the API that Jagex's moderators review closely, which the bindings mark {@code @LuaReview}, and the
 * warning at a use in one: what the use does, what moderation looks at there, and how to silence the area's warnings
 * once the plugin meets that. Both front ends warn alike; only the annotation that silences a warning, and where it
 * goes, is each language's own.
 */
public final class ReviewAreas {

	private ReviewAreas() {
	}

	/**
	 * The warning at a use.
	 *
	 * @param use the use as the warning starts it: {@code Chat.AddMessage}, or {@code Subscribing to ChatMessage}
	 * @param area the area the bindings give the member, such as {@code chat}
	 * @param language the plugin's language, whose annotation silences the area's warnings: Java's
	 *     {@code @SuppressWarnings} on a method or class, Kotlin's {@code @Suppress} on a function, class or file
	 */
	public static String warning(String use, String area, SourceLanguage language) {
		String silencer = switch (language) {
			case JAVA -> "@SuppressWarnings(\"oneiro:" + area + "\") on the method or class";
			case KOTLIN -> "@Suppress(\"oneiro:" + area + "\") on the function, class or file";
		};
		return use + " " + lookedAt(area) + " Once the plugin meets this, " + silencer + " silences this warning.";
	}

	/** The key of an area's warnings, which the silencing annotation names: {@code oneiro:chat}. */
	public static String key(String area) {
		return "oneiro:" + area;
	}

	/** What a use in the area does, and what moderation looks at there. */
	private static String lookedAt(String area) {
		return switch (area) {
			case "chat" -> "uses the chat, which Jagex's moderators review closely: the Mod.io description must say"
					+ " what the plugin uses chatAccess for, and other players' messages are information about them,"
					+ " which a plugin must never export.";
			case "http" -> "connects to a server outside the game, which Jagex's moderators review closely: the"
					+ " Mod.io description must say what the plugin uses httpAccess for, what it sends where, and which"
					+ " data protection case applies; information about other players must never be sent.";
			case "tracking" -> "reads the character's tracking ID, which Jagex's moderators review closely: the Mod.io"
					+ " description must say what the plugin uses trackingAccess for.";
			case "mini-menu" -> "changes the right-click menu, which Jagex's moderators review closely as input"
					+ " customisation: a change must not reduce the game's intended difficulty.";
			case "other-players" -> "finds other players, which Jagex's moderators review closely: information about"
					+ " other players must never be exported, nor used to scout, target or track opponents in PvP.";
			default -> throw new IllegalStateException("a review area the bindings should not give: " + area);
		};
	}
}
