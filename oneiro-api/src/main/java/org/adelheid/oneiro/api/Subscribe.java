package org.adelheid.oneiro.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Subscribes a method of the {@link Plugin} class to a client event: the type of its one parameter, an event payload,
 * names the event. The subscription is made after {@code startUp()} and removed in {@code PluginShutdown}, under the
 * reference name {@code "<plugin id>.<method name>"}:
 *
 * <pre>{@code
 * @Subscribe
 * public void onInventoryUpdated(InventoryUpdatedEventPayload event) { ... }
 * // Event.InventoryUpdated.Subscribe("herbs.onInventoryUpdated", onInventoryUpdated)
 * }</pre>
 *
 * <p>In Kotlin, the same:
 *
 * <pre>{@code
 * @Subscribe
 * fun onInventoryUpdated(event: InventoryUpdatedEventPayload) { ... }
 * }</pre>
 *
 * <p>For an event that passes nothing, such as {@code ServerLogic}, the parameter is its empty payload class and the
 * Lua function takes no parameter.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Subscribe {
}
