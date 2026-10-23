package org.adelheid.oneiro.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the payload class of a client event, so that a {@link Subscribe} method taking it subscribes to that event:
 * a method taking the payload of {@code @LuaEvent("InventoryUpdated")} becomes
 * {@code Event.InventoryUpdated.Subscribe(...)}.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface LuaEvent {

	/** The event's name, the field of the client's {@code Event} table. */
	String value();

	/**
	 * What a callback returns: {@code void.class}, or {@code boolean.class} for an event such as {@code EntityDraw}.
	 */
	Class<?> returns() default void.class;
}
