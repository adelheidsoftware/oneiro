package org.adelheid.oneiro.api.binding;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an API member that only works with a permission the plugin's manifest requests, such as
 * {@code chatAccess} for {@code Chat.AddMessage}. Code that uses it fails to compile unless the build declares the
 * permission; Oneiro never adds a permission on its own. On a class, it covers every member.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface LuaPermission {

	/** The permission's manifest name: {@code httpAccess}, {@code chatAccess} or {@code trackingAccess}. */
	String value();
}
