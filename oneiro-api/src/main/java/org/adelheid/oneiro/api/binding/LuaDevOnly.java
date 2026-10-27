package org.adelheid.oneiro.api.binding;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an API member that must never ship, such as {@code ExecuteConsoleCommand}, which runs development cheats.
 * Using it fails to compile except in the plugin's {@code dev} source set, whose Lua stays out of the zip.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface LuaDevOnly {
}
