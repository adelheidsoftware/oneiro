package org.adelheid.oneiro.java.plugin;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import java.util.Arrays;
import javax.lang.model.element.Element;
import org.adelheid.oneiro.java.core.UnitLowering;
import org.adelheid.oneiro.lowering.SourceLanguage;
import org.adelheid.oneiro.lowering.client.ReviewAreas;

/**
 * Warnings at uses of the API that Jagex's moderators review closely, which the bindings mark {@code @LuaReview} with
 * an area. Each warning names what moderation looks at in that area, so that the developer meets it before submitting
 * rather than in a moderator's report. Once the plugin does, {@code @SuppressWarnings("oneiro:<area>")} on a method or
 * class around the use silences the area's warnings there, as it silences javac's own. The dev source set never
 * ships, so its uses are not warned about.
 */
public final class ReviewWarnings {

	private final UnitLowering unit;

	public ReviewWarnings(UnitLowering unit) {
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
		unit.warning(unit.start(where), ReviewAreas.warning(use, area, SourceLanguage.JAVA));
	}

	/** Whether a method, class or variable declaration around the use carries {@code @SuppressWarnings} for it. */
	private boolean isSuppressed(Tree where, String area) {
		String key = ReviewAreas.key(area);
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
