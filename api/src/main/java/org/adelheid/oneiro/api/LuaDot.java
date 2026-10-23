package org.adelheid.oneiro.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an instance method that Lua calls with a dot, because it is a function stored in the object's field rather
 * than a method that takes the object: {@code Event.Logic.Subscribe(name, callback)}.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface LuaDot {
}
