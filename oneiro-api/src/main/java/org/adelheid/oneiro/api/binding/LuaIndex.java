package org.adelheid.oneiro.api.binding;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method with one parameter that stands for indexing the table: {@code ui.StyleSheets.get(id)} is
 * {@code ui.StyleSheets[id]}, on the global table for a static method and on the object for an instance one.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface LuaIndex {
}
