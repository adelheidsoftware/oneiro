package org.adelheid.oneiro.java;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.ElementFilter;
import org.adelheid.oneiro.lowering.Inlining;
import org.adelheid.oneiro.lua.Block;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.FunctionBody;
import org.jspecify.annotations.Nullable;

/**
 * Lambdas and method references as Lua functions, and a lambda's code opened in place where a loop or a stream
 * calls it, so that the loop's body reads the lambda's parameters by their own names.
 */
final class Lambdas {

	private final BodyLowering body;
	private final UnitLowering unit;
	private final MethodReferences methodReferences;
	private final JavaTypes types;

	Lambdas(BodyLowering body) {
		this.body = body;
		this.unit = body.unit;
		this.methodReferences = body.methodReferences();
		this.types = body.types();
	}

	Expression lambda(LambdaExpressionTree lambda) {
		checkFunctionTarget(lambda);
		BodyLowering inner = new BodyLowering(unit, body.hasSelf, false);
		inner.inherit(body);
		List<String> parameters = lambda.getParameters().stream().map(inner::declare).toList();
		// Lua drops the arguments a function does not name, so unnamed parameters at the end are left out:
		// (_, x, _) -> ... is function(_, x)
		int named = parameters.size();
		while (named > 0 && parameters.get(named - 1).equals("_")) {
			named--;
		}
		parameters = List.copyOf(parameters.subList(0, named));
		// An event that passes nothing calls its callbacks without a value, so the payload parameter has no Lua form
		if (lambda.getParameters().size() == 1) {
			VariableTree payload = lambda.getParameters().getFirst();
			JavaTypes.Event event = eventWithoutPayload(payload);
			if (event != null) {
				if (body.uses(lambda.getBody(), unit.element(payload))) {
					throw new Rejection(payload, "The " + event.name() + " event passes nothing, so its payload has"
							+ " nothing to read; leave " + payload.getName() + " unused.");
				}
				parameters = List.of();
			}
		}
		Block code;
		if (lambda.getBody() instanceof BlockTree block) {
			code = inner.block(block);
		} else {
			ExpressionTree expression = (ExpressionTree) lambda.getBody();
			code = BodyLowering.blockOf(
					unit.returnsVoid(lambda) ? inner.expressionStatement(expression) : inner.returned(expression));
		}
		// The return null; that a function returning Void needs in Java is what a Lua function does by ending
		if (types.returnsVoidObject(unit.type(lambda))) {
			code = code.withoutReturningNil();
		}
		return new Expression.FunctionExpression(FunctionBody.of(parameters, code));
	}

	/**
	 * A lambda's code taken in place, as the body of the loop a {@code forEach} or {@code removeIf} becomes.
	 *
	 * @param parameters the Lua names of the lambda's parameters
	 * @param body the lambda's code as statements; for an expression body, the expression as a statement
	 * @param value an expression body, which a predicate's loop tests
	 */
	record InlinedLambda(List<String> parameters, Block body, @Nullable ExpressionTree value) {
	}

	/**
	 * A lambda written where Lua can take its code in place. Its parameters are declared here, as the loop's
	 * variables, so that its code reads them by their own names. Null for anything else: a method reference, a
	 * function held in a variable, or a block that returns early, which only a function can do.
	 */
	@Nullable InlinedLambda inline(ExpressionTree function) {
		return inline(function, null);
	}

	/**
	 * A lambda's code in place, as {@link #inline(ExpressionTree)}, with its parameters standing for the given locals
	 * instead of declaring their own, as a stream's stages read the loop's element.
	 */
	@Nullable InlinedLambda inline(ExpressionTree function, @Nullable List<String> standingFor) {
		if (!(BodyLowering.withoutParentheses(function) instanceof LambdaExpressionTree lambda)
				|| returnsEarly(lambda)) {
			return null;
		}
		List<String> parameters = new ArrayList<>();
		for (int index = 0; index < lambda.getParameters().size(); index++) {
			VariableTree parameter = lambda.getParameters().get(index);
			String declared = body.declare(parameter);
			if (standingFor != null) {
				body.alias(parameter, standingFor.get(index));
				declared = standingFor.get(index);
			}
			parameters.add(declared);
		}
		if (lambda.getBody() instanceof BlockTree block) {
			return new InlinedLambda(parameters, body.block(block), null);
		}
		ExpressionTree expression = (ExpressionTree) lambda.getBody();
		Block asStatement = unit.returnsVoid(lambda)
				? BodyLowering.blockOf(body.expressionStatement(expression)) : Block.of();
		return new InlinedLambda(parameters, asStatement, expression);
	}

	/**
	 * A function value called on arguments. A method reference's small function,
	 * {@code function(npc) return self:isTarget(npc) end}, is opened up into the call it makes,
	 * {@code self:isTarget(x)}; see {@link Inlining}. Anything else is called as it is.
	 */
	Expression applied(Expression function, List<Expression> arguments) {
		Expression opened = Inlining.value(function, arguments);
		return opened != null ? opened : new Expression.Call(function, arguments);
	}

	/** Whether a block lambda returns anywhere but at its end, outside the lambdas and classes inside it. */
	static boolean returnsEarly(LambdaExpressionTree lambda) {
		if (!(lambda.getBody() instanceof BlockTree block)) {
			return false;
		}
		return new TreeSearch() {
			@Override
			public Boolean visitReturn(ReturnTree node, Void unused) {
				return true;
			}

			@Override
			public Boolean visitLambdaExpression(LambdaExpressionTree node, Void unused) {
				return false;
			}

			@Override
			public Boolean visitClass(ClassTree node, Void unused) {
				return false;
			}
		}.foundIn(block);
	}

	/** The event whose payload the parameter is, when that event passes nothing; null for any other parameter. */
	private JavaTypes.@Nullable Event eventWithoutPayload(VariableTree parameter) {
		if (unit.type(parameter) instanceof DeclaredType declared && declared.asElement() instanceof TypeElement payload
				&& ElementFilter.fieldsIn(payload.getEnclosedElements()).isEmpty()) {
			return types.event(payload);
		}
		return null;
	}

	/**
	 * A lambda or method reference is a Lua function, which cannot stand in for an interface that a class implements:
	 * code calls that interface's methods with a colon, as methods of an object.
	 */
	private void checkFunctionTarget(Tree function) {
		TypeElement target = types.pluginType(unit.type(function));
		if (target != null && types.isImplemented(target)) {
			throw new Rejection(function, target.getSimpleName() + " is implemented by a class, so its values are Lua"
					+ " objects with methods, which a function cannot stand in for; implement it with a class"
					+ " here too.");
		}
	}

	/** A method reference as a Lua function; see {@link MethodReferences}. */
	Expression memberReference(MemberReferenceTree reference) {
		checkFunctionTarget(reference);
		return methodReferences.lower(reference);
	}
}
