package org.adelheid.oneiro.gradle.workers;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.gradle.api.GradleException;

/**
 * Calls into Oneiro's JDK 25 code from the worker processes, by name: this plugin is compiled for an older Java, so
 * it cannot link against that code, and the calls take and return only strings and lists.
 */
final class Reflection {

	private Reflection() {
	}

	/** Calls a public static method whose parameters are all {@code String}. */
	static Object call(String className, String method, String... arguments) {
		try {
			Class<?>[] types = new Class<?>[arguments.length];
			Arrays.fill(types, String.class);
			Method target = Class.forName(className).getMethod(method, types);
			Object result = target.invoke(null, (Object[]) arguments);
			if (result == null) {
				throw new GradleException(className + "." + method + " returned nothing.");
			}
			return result;
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause() == null ? e : e.getCause();
			throw new GradleException(String.valueOf(cause.getMessage()), cause);
		} catch (ReflectiveOperationException e) {
			throw new GradleException("Oneiro's tools are missing from the worker's classpath: " + e.getMessage(), e);
		}
	}
}
