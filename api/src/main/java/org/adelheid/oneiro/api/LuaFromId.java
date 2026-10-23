package org.adelheid.oneiro.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an API enum whose Lua table has {@code FromID}, which gives a value's name: {@code state.name()} is
 * {@code GameState.FromID(state)}. A value of an enum without it has no name in Lua.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface LuaFromId {
}
