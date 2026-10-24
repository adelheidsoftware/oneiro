package org.adelheid.oneiro.java;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import java.util.Arrays;
import javax.lang.model.element.Element;

/**
 * Warnings at uses of the API that Jagex's moderators review closely, which the bindings mark {@code @LuaReview} with
 * an area. Each warning names what moderation looks at in that area, so that the developer meets it before submitting
 * rather than in a moderator's report. Once the plugin does, {@code @SuppressWarnings("oneiro:<area>")} on a method or
 * class around the use silences the area's warnings there, as it silences javac's own. The dev source set never
 * ships, so its uses are not warned about.
 */
final class ReviewWarnings {

	private final UnitLowering unit;

	ReviewWarnings(UnitLowering unit) {
		this.unit = unit;
	}

	/**
	 * Warns at a use of a reviewed member, unless the code around it silences the member's area.
	 *
	 * @param marked the member, or the event payload, that may carry {@code @LuaReview}
	 * @param use the use as the warning starts it: {@code Chat.AddMessage}, or {@code Subscribing to ChatMessage}
	 */
	void check(Element marked, String use, Tree where) {
		String area = unit.types.reviewArea(marked);
		if (area == null || unit.plugin.dev() || isSuppressed(where, area)) {
			return;
		}
		unit.warning(unit.start(where), use + " " + lookedAt(area) + " Once the plugin meets this,"
				+ " @SuppressWarnings(\"oneiro:" + area + "\") on the method or class silences this warning.");
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

	/** Whether a method, class or variable declaration around the use carries {@code @SuppressWarnings} for it. */
	private boolean isSuppressed(Tree where, String area) {
		String key = "oneiro:" + area;
		for (TreePath path = unit.path(where); path != null; path = path.getParentPath()) {
			Tree tree = path.getLeaf();
			if (tree instanceof ClassTree || tree instanceof MethodTree || tree instanceof VariableTree) {
				Element declared = unit.trees.getElement(path);
				SuppressWarnings suppressed = declared == null ? null : declared.getAnnotation(SuppressWarnings.class);
				if (suppressed != null && Arrays.asList(suppressed.value()).contains(key)) {
					return true;
				}
			}
		}
		return false;
	}
}
