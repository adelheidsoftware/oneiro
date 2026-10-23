package org.adelheid.oneiro.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * One setting of a {@link ConfigGroup} interface: its getter, a default method returning the default value, or its
 * setter, an abstract {@code void} method with one parameter. A setting may be a {@code boolean}, an {@code int} or
 * {@code long}, a {@code double}, a {@code String}, or an enum of the plugin's.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface ConfigItem {

	/** The key the setting is stored under, after its group's prefix and a dot. */
	String keyName();

	/** The setting's name for people, shown in the comment above its Lua function. */
	String name();

	/** What the setting does, shown in the comment above its Lua function. */
	String description() default "";
}
