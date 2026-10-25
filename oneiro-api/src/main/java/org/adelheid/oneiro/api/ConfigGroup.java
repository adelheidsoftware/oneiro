package org.adelheid.oneiro.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares an interface of settings kept in the client's {@code PersistentDB}, one per {@link ConfigItem} method. The
 * interface becomes a Lua module with a function per method: a method with a body, a default method in Java, is a
 * getter that returns its stored value, or its body's value when nothing is stored, and a method without a body that
 * takes one parameter and returns nothing is the matching setter. Each setting is stored under
 * {@code "<group>.<keyName>"} with the typed accessor of its type. In Java:
 *
 * <pre>{@code
 * @ConfigGroup("herbs")
 * public interface HerbConfig {
 *     @ConfigItem(keyName = "showOverlay", name = "Show overlay", description = "Draws a box on each patch")
 *     default boolean showOverlay() {
 *         return true;
 *     }
 *
 *     @ConfigItem(keyName = "showOverlay", name = "Show overlay", description = "Draws a box on each patch")
 *     void setShowOverlay(boolean show);
 * }
 * }</pre>
 *
 * <p>In Kotlin:
 *
 * <pre>{@code
 * @ConfigGroup("herbs")
 * interface HerbConfig {
 *     @ConfigItem(keyName = "showOverlay", name = "Show overlay", description = "Draws a box on each patch")
 *     fun showOverlay(): Boolean = true
 *
 *     @ConfigItem(keyName = "showOverlay", name = "Show overlay", description = "Draws a box on each patch")
 *     fun setShowOverlay(show: Boolean)
 * }
 * }</pre>
 *
 * <p>A field of the interface's type, a {@code lateinit var} in Kotlin, needs no value: every call through it is a
 * call of the module.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface ConfigGroup {

	/** The prefix of every key in the group, such as {@code herbs}. */
	String value();
}
