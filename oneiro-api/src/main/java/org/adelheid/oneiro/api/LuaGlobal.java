package org.adelheid.oneiro.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a class whose static members are the fields of a Lua global table that the client, or another plugin,
 * provides: {@code @LuaGlobal("Player")} makes {@code Player.GetEntity()} the Lua {@code Player.GetEntity()}, and
 * {@code @LuaGlobal("ui.Text")} makes {@code new ui.Text(parent)} the Lua {@code ui.Text.new(parent)}. The class
 * produces no Lua of its own. With {@code ""}, the static members are globals themselves: {@code Globals.log(text)} is
 * {@code log(text)}.
 *
 * <p>Its static methods are called with a dot, or with a colon when they carry {@link LuaColon}; its constructors are
 * the table's {@code new} function; its instance methods and fields belong to the table's objects, called as
 * {@code object:method(...)} and read as {@code object.field}. Oneiro's RS3 bindings are declared this way.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface LuaGlobal {

	/** The global table's path, such as {@code Player} or {@code ui.Text}, or {@code ""} for the globals themselves. */
	String value();
}
