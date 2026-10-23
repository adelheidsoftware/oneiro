package org.adelheid.oneiro.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a Lua module that already exists, hand-written or from another plugin, so that Java code can use it. The
 * class produces no Lua of its own: its methods are {@code native}, its fields hold no values, and code that uses it
 * loads the module with {@code require} and calls it there.
 *
 * <pre>{@code
 * @LuaModule("src/core/levels")
 * public final class Levels {
 *     public static int PERFECT;
 *     public static native int fromPenalty(int penalty);
 * }
 * }</pre>
 *
 * <p>Static methods and fields become {@code Levels.fromPenalty(...)} and {@code Levels.PERFECT}; instance methods are
 * methods of the module's objects, called as {@code object:method(...)}. A module's constructor is declared as a
 * static method with {@code @LuaName("new")}.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface LuaModule {

	/** The module's require path inside the plugin, such as {@code src/core/levels}. */
	String value();
}
