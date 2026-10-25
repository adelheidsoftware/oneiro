package org.adelheid.oneiro.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class of the generated bindings that stands for a type of Lua values that existing Lua hands out, with no
 * table of its own for code to reach: an entity the client passes, an event's payload, a settings table. Its instance
 * methods are called with a colon, {@code npc:GetName()}, or with a dot when they carry {@link LuaDot}; its fields are
 * the table's fields. A public constructor without parameters, where the type has one, builds an empty table,
 * {@code {}}, for the plugin to fill and pass to the API. The class produces no Lua of its own. Only generated bindings
 * carry it.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface LuaType {

	/** The type's name in Lua Language Server annotations, such as {@code NPCEntity}. */
	String value();
}
