package org.adelheid.oneiro.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an API method that only changes the type of its receiver and is the receiver itself in Lua:
 * {@code ui.Hook.ONCLICK.stoppable()} is {@code ui.Hook.ONCLICK}, typed so that its callback returns a boolean.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface LuaSame {
}
