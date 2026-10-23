package org.adelheid.oneiro.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a record that stands for the several values a Lua function returns, in the order of its components. A call
 * whose type it is initialises a local, and each component becomes a Lua local of its own:
 *
 * <pre>{@code
 * @LuaReturnValues
 * public record GroundHeight(int height, boolean available) {}
 *
 * var ground = World.GetGroundHeight(level, x, z);   // local height, available = World.GetGroundHeight(level, x, z)
 * if (ground.available()) { ... }                    // if available then ... end
 * }</pre>
 *
 * <p>A plugin method may return one too: {@code return new GroundHeight(h, true);} is {@code return h, true}.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface LuaReturnValues {
}
