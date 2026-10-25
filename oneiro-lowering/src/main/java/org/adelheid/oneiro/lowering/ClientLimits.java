package org.adelheid.oneiro.lowering;

import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What the client has nothing for, which plugins written for other clients reach for: plugins run on the client's one
 * thread, so threads, executors, futures and timers have nothing to run on, and Lua's {@code os} library, with its
 * clock, is not there (checked in the client, 2026-10-23). Both front ends reject these with the same reasons.
 */
public final class ClientLimits {

	private static final String COUNT_TICKS = "count the ticks of " + ClientApi.EVENT + ".ServerLogic, about every"
			+ " 0.6 seconds, or of " + ClientApi.EVENT + ".Logic, 50 a second.";

	/** The reason a plugin cannot start threads or schedule work. */
	public static final String NO_THREADS = "Plugins run on the client's one thread and cannot start threads or"
			+ " schedule work of their own; do the work in an event callback, and to do it later, " + COUNT_TICKS;

	/** The reason a plugin cannot read the time. */
	public static final String NO_CLOCK = "The client gives plugins no clock to read; to measure time, " + COUNT_TICKS;

	/**
	 * The types whose subtypes run code on threads of their own, which a front end tests a class against: executors,
	 * futures and stages of asynchronous work.
	 */
	public static final Set<String> CONCURRENT_SUPERTYPES = Set.of("java.util.concurrent.Executor",
			"java.util.concurrent.Future", "java.util.concurrent.CompletionStage");

	private static final Set<String> CONCURRENT = Set.of("java.lang.Thread", "java.util.Timer", "java.util.TimerTask",
			"javax.swing.Timer", "java.util.concurrent.Executors", "kotlin.concurrent");

	private ClientLimits() {
	}

	/**
	 * Whether a member reads a clock: {@code System.currentTimeMillis} and {@code nanoTime}, {@code java.time}'s
	 * {@code now()}, a {@code Clock}, Kotlin's {@code measureTimeMillis} and its time sources.
	 *
	 * @param owner the class that declares the member, or the package of a function outside any class
	 * @param member the member's name, or null for a constructor
	 */
	public static boolean readsClock(String owner, @Nullable String member) {
		if (owner.equals("java.time.Clock") || owner.startsWith("kotlin.time.TimeSource")) {
			return true;
		}
		if (member == null) {
			return false;
		}
		boolean system = owner.equals("java.lang.System")
				&& (member.equals("currentTimeMillis") || member.equals("nanoTime"));
		boolean now = owner.startsWith("java.time.") && member.equals("now");
		boolean measured = owner.equals("kotlin.system")
				&& (member.equals("measureTimeMillis") || member.equals("measureNanoTime"));
		return system || now || measured;
	}

	/**
	 * Whether a class, or the package of a function outside any class, runs code on a thread of its own: a thread, a
	 * timer, the executors, or Kotlin's {@code thread} and {@code timer}.
	 */
	public static boolean isConcurrent(String owner) {
		return CONCURRENT.contains(owner);
	}
}
