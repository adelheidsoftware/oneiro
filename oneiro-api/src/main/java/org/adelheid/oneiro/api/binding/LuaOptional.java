package org.adelheid.oneiro.api.binding;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the class of an optional dependency's global, a library plugin the player may not have, or may turn off while
 * the plugin runs: {@code gauges} for {@code optional("gauges", 5151)}. Its {@code isLoaded()} is the check,
 * {@code if gauges then} in Lua, and code that reaches the global, a static member of the class or of a class nested
 * in it, compiles only where that check reaches it. A value the global handed out, such as a gauge, needs no check.
 * Only generated bindings carry it.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface LuaOptional {
}
