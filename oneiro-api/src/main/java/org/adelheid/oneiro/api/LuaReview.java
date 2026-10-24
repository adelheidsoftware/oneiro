package org.adelheid.oneiro.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an API member whose use Jagex's moderators review closely, such as {@code MiniMenu.Swap}, which changes the
 * right-click menu. Code that calls the method or constructor, assigns the field, or subscribes to the event that
 * carries it on its field or payload compiles with a warning that names what moderation looks at. Once the plugin
 * meets that, {@code @SuppressWarnings("oneiro:<area>")} on the method or class around the code silences the area's
 * warnings.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface LuaReview {

	/**
	 * The area moderation reviews: {@code chat}, {@code http}, {@code mini-menu}, {@code other-players} or
	 * {@code tracking}.
	 */
	String value();
}
