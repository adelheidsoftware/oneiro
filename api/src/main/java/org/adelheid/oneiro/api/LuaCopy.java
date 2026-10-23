package org.adelheid.oneiro.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an API field whose value the client hands out as a copy, such as {@code ShapeInstance.translation}: changing
 * the copy in place, {@code instance.translation.y = 5}, changes nothing in the client, so it fails to compile. Change
 * a copy and assign it back instead.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
public @interface LuaCopy {
}
