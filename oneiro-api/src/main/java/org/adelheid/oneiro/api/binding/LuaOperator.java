package org.adelheid.oneiro.api.binding;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an API method that stands for one of Lua's operators on the client's values: {@code a.plus(b)} on two
 * {@code Vector3}s is {@code a + b} in Lua. The value is the operator: {@code +}, {@code -}, {@code *} or {@code /}.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface LuaOperator {

	/** The Lua operator, with the receiver on its left and the argument on its right. */
	String value();
}
