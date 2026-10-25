package org.adelheid.oneiro.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a Lua module that already exists, hand-written or from another plugin, so that the plugin's code can use
 * it. The declaration produces no Lua of its own: its methods have no body, and code that uses it loads the module
 * with {@code require} and calls it there. In Java, a class whose methods are {@code native} and whose fields hold no
 * values:
 *
 * <pre>{@code
 * @LuaModule("src/core/levels")
 * public final class Levels {
 *     public static int PERFECT;
 *     public static native int fromPenalty(int penalty);
 * }
 * }</pre>
 *
 * <p>In Kotlin, an object whose functions are {@code external}, and whose properties have external accessors:
 *
 * <pre>{@code
 * @LuaModule("src/core/levels")
 * object Levels {
 *     val PERFECT: Int
 *         external get
 *
 *     external fun fromPenalty(penalty: Int): Int
 * }
 * }</pre>
 *
 * <p>Static methods and fields, or an object's functions and properties, become {@code Levels.fromPenalty(...)} and
 * {@code Levels.PERFECT}; instance methods are methods of the module's objects, called as {@code object:method(...)}.
 * A module's constructor is declared as a static method with {@code @LuaName("new")}.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface LuaModule {

	/** The module's require path inside the plugin, such as {@code src/core/levels}. */
	String value();
}
