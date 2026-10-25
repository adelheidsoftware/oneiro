package org.adelheid.oneiro.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The name a method, field or record component has in the Lua, when it differs from its name in the source: to keep a
 * name that existing Lua code calls, as in {@code @LuaName("Ingest") public void ingest(...)} in Java or
 * {@code @LuaName("Ingest") fun ingest(...)} in Kotlin, or to use a Lua name the source cannot spell, as in
 * {@code @LuaName("new")}. Every call and access in the plugin uses the Lua name.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface LuaName {

	/** The Lua name: ASCII letters, digits and underscores, not starting with a digit and not a Lua keyword. */
	String value();
}
