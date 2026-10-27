package org.adelheid.oneiro.lowering.client;

/**
 * The rule both front ends hold code to on an optional dependency, a library plugin the player may not have, or may
 * turn off while the plugin runs: code that reaches its global compiles only where a check that it is loaded reaches
 * it, in the same function. A check does not reach into a lambda, or a local or anonymous class, whose code runs
 * later, when the library may be gone; a value the global handed out, such as a view, needs none.
 */
public final class OptionalDependencies {

	private OptionalDependencies() {
	}

	/** Why a use of an optional dependency's global that no check reaches does not compile. */
	public static String unchecked(String alias) {
		return alias + " is an optional dependency, which the player may not have, or may turn off while the plugin"
				+ " runs; reach it where a check does: inside if (" + alias + ".isLoaded()) { ... }, or after if (!"
				+ alias + ".isLoaded()) return; in the same function. Code in a lambda checks again, as it runs later.";
	}

	/**
	 * Why a reference to an optional dependency's {@code isLoaded()} does not compile: in Lua the check is the global
	 * itself, so no function stands for it.
	 *
	 * @param lambda the lambda that says the same in the plugin's language, {@code () -> gauges.isLoaded()}
	 */
	public static String reference(String lambda) {
		return "isLoaded() checks the dependency's global, which is no function of the library's; write the reference"
				+ " as a lambda, " + lambda + ", which checks it when it is called.";
	}
}
