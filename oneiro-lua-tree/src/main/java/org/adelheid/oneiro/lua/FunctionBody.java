package org.adelheid.oneiro.lua;

import java.util.List;

/**
 * The parameters and body of a function, shared by function statements and function expressions.
 *
 * @param parameters the parameter names, without {@code self}, which a method declaration implies
 * @param varargs whether the parameter list ends with {@code ...}
 * @param body the function's statements
 */
public record FunctionBody(List<String> parameters, boolean varargs, Block body) {

	public FunctionBody {
		parameters = List.copyOf(parameters);
		parameters.forEach(LuaNames::requireName);
	}

	public static FunctionBody of(List<String> parameters, Block body) {
		return new FunctionBody(parameters, false, body);
	}
}
