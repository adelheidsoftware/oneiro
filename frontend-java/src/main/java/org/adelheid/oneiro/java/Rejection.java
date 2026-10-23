package org.adelheid.oneiro.java;

import com.sun.source.tree.Tree;

/**
 * Thrown while lowering when a construct has no Lua translation, yet or ever. The member being lowered is skipped and
 * the rejection becomes an error at the tree's position, so one run reports every problem in a file.
 */
final class Rejection extends RuntimeException {

	private final transient Tree tree;
	private final String message;

	Rejection(Tree tree, String message) {
		super(message, null, false, false);
		this.tree = tree;
		this.message = message;
	}

	/** A construct that docs/java-subset.md lists as planned but not yet supported. */
	static Rejection notYet(Tree tree, String construct) {
		return new Rejection(tree, construct + " is not supported yet (see docs/java-subset.md).");
	}

	Tree tree() {
		return tree;
	}

	String message() {
		return message;
	}
}
