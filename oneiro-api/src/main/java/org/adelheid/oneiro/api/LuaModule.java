package org.adelheid.oneiro.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class of the generated bindings that stands for a Lua module, the table {@code require} gives: a module of
 * the plugin's own Lua, whose bindings Oneiro generates from its LuaCATS annotations, so that
 * {@code src/main/lua/core/levels.lua} is the class {@code <root>.core.Levels}, annotated
 * {@code @LuaModule("src/core/levels")} and {@code @LuaGlobal("Levels")}. Code that uses the class, or a class nested
 * in it, requires the module into a local of the class's name, {@code local Levels = require("src/core/levels")}, where
 * the path its {@link LuaGlobal} gives starts.
 *
 * <p>Only generated bindings carry it. The plugin's own Lua is bound from its annotations, so a class of the plugin's
 * own that carries this annotation does not compile.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface LuaModule {

	/** The path {@code require} loads the module by, such as {@code src/core/levels}. */
	String value();
}
