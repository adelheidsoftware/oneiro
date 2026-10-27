package org.adelheid.oneiro.api.binding;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class of the generated bindings whose static members are the fields of a Lua table: a global one, as
 * {@code @LuaGlobal("Player")} makes {@code Player.GetEntity()} the Lua {@code Player.GetEntity()} and
 * {@code @LuaGlobal("ui.Text")} makes {@code new ui.Text(parent)} the Lua {@code ui.Text.new(parent)}; or, in a class
 * of a {@link LuaModule}, one the module's local reaches. The class produces no Lua of its own. With {@code ""}, the
 * static members are globals themselves: {@code Globals.log(text)} is {@code log(text)}.
 *
 * <p>Its static methods are called with a dot, or with a colon when they carry {@link LuaColon}; its constructors are
 * the table's {@code new} function; its instance methods and fields belong to the table's objects, called as
 * {@code object:method(...)} and read as {@code object.field}. Only generated bindings carry it: Oneiro's RS3 bindings,
 * the bindings of the plugin's own Lua, and the plugin's generated {@code Resources} and {@code Urls}.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface LuaGlobal {

	/** The table's path, such as {@code Player} or {@code ui.Text}, or {@code ""} for the globals themselves. */
	String value();
}
