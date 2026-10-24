package org.adelheid.oneiro.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a static method of a {@link LuaGlobal} class that Lua calls with a colon on the table, because the table is a
 * singleton object rather than a namespace: {@code PersistentDB.GetBool(key)} is {@code PersistentDB:GetBool(key)}.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface LuaColon {
}
