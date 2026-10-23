package org.adelheid.oneiro.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class of the RS3 bindings whose static fields are the names of one of the client's constant tables,
 * {@code id}, {@code config} or {@code var}. Each field's Java name is {@link #PREFIX} followed by its Lua name, so that
 * every name is one Java can spell, those that start with a digit included: {@code id.Obj.RS3_COINS} is
 * {@code id.Obj.COINS} in Lua, and {@code id.Seq.RS3_1ST4H_GUARD_GOBLIN_EAT} is
 * {@code id.Seq["1ST4H_GUARD_GOBLIN_EAT"]}.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface LuaConstants {

	/** What every constant's Java name starts with, before its Lua name. */
	String PREFIX = "RS3_";
}
