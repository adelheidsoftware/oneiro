package org.adelheid.oneiro.java.core;

import com.sun.source.tree.Tree;
import com.sun.source.util.TreeScanner;

/**
 * A search of a tree for a node of some kind. Each visit method a search overrides returns whether its node matches;
 * any other node matches when a node inside it does, and an override that returns false keeps the search out of its
 * node, as a search for a {@code return} stays out of the lambdas inside a method.
 */
public abstract class TreeSearch extends TreeScanner<Boolean, Void> {

	/** Whether the tree, or a node inside it, matches. */
	public final boolean foundIn(Tree tree) {
		return Boolean.TRUE.equals(scan(tree, null));
	}

	@Override
	public final Boolean reduce(Boolean first, Boolean second) {
		return Boolean.TRUE.equals(first) || Boolean.TRUE.equals(second);
	}
}
