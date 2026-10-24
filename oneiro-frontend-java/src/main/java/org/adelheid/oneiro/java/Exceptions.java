package org.adelheid.oneiro.java;

import static org.adelheid.oneiro.lua.Expression.call;
import static org.adelheid.oneiro.lua.Expression.integer;
import static org.adelheid.oneiro.lua.Expression.name;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ContinueTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.tree.YieldTree;
import com.sun.source.util.TreeScanner;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeMirror;
import org.adelheid.oneiro.lua.Block;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.FunctionBody;
import org.adelheid.oneiro.lua.Statement;
import org.adelheid.oneiro.lua.Trivia;
import org.adelheid.oneiro.lua.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * Java's exceptions in Lua, where an error is a value that {@code error} raises and {@code pcall} catches: a
 * {@code throw} raises the exception's message, a try block runs in a function that {@code pcall} calls, a
 * catch block runs when that function raised, and a finally block, or the closing of a try-with-resources, runs
 * either way before the error goes on. A break, continue or yield cannot leave the function {@code pcall} runs,
 * so one that would leave a try block is rejected.
 */
final class Exceptions {

	private final BodyLowering body;
	private final UnitLowering unit;
	private final StringBuilding strings;
	private final JavaTypes types;

	Exceptions(BodyLowering body) {
		this.body = body;
		this.unit = body.unit;
		this.strings = body.strings();
		this.types = body.types();
	}

	/**
	 * {@code throw new SomeException(message)} becomes {@code error(message)}; only the message survives. A caught
	 * error thrown again is raised as it is, {@code error(e, 0)}, so that its message keeps the place it was raised.
	 */
	Statement throwStatement(ThrowTree thrown) {
		if (thrown.getExpression() instanceof IdentifierTree caught && isCaughtError(caught)) {
			return new Statement.CallStatement(call(name("error"), body.expression(caught), integer(0)));
		}
		if (!(thrown.getExpression() instanceof NewClassTree creation) || creation.getClassBody() != null) {
			// An exception kept in a local is its message, the value error raises
			return new Statement.CallStatement(call(name("error"), body.expression(thrown.getExpression())));
		}
		return new Statement.CallStatement(errorCall(creation));
	}

	/** {@code error(message)} for a new exception: its message, or its class's name when it has none. */
	Expression errorCall(NewClassTree creation) {
		return call(name("error"), errorMessage(creation));
	}

	/** A new exception as Lua's error value: its message, or its class's name when it has none. */
	Expression errorMessage(NewClassTree creation) {
		return creation.getArguments().isEmpty()
				? Expression.string(unit.type(creation).toString().replaceAll(".*\\.", ""))
				: strings.asText(creation.getArguments().getFirst());
	}

	/**
	 * {@code try { ... } catch (RuntimeException e) { ... }} as {@code pcall}: the try block becomes a function that
	 * {@code pcall} runs, and the catch block runs when it raised an error, with {@code e} the error value.
	 *
	 * <pre>{@code
	 * local ok, e = pcall(function()
	 *     ...
	 * end)
	 * if not ok then
	 *     ...
	 * end
	 * }</pre>
	 *
	 * <p>A try block that returns must return on every path, and its value is returned after {@code pcall}:
	 * {@code if ok then return result else ... end}. Lua errors carry a value, not a class, so the one catch clause
	 * names a class that catches everything.
	 */
	List<Block.Entry> tryStatement(TryTree attempt) {
		List<? extends CatchTree> catches = attempt.getCatches();
		if (catches.size() > 1) {
			throw new Rejection(catches.get(1), "Lua errors carry a value, not a class, so one catch takes every error;"
					+ " catch RuntimeException once, and tell the errors apart inside it.");
		}
		CatchTree handler = catches.isEmpty() ? null : catches.getFirst();
		if (handler != null && !isCatchAll(unit.type(handler.getParameter()))) {
			throw new Rejection(handler.getParameter().getType(), "Lua errors carry a value, not a class, so a catch"
					+ " cannot tell exception classes apart; catch RuntimeException, Exception or Throwable, and tell"
					+ " the errors apart inside it.");
		}
		BlockTree block = attempt.getBlock();
		checkStaysInside(block);
		boolean returns = completesByReturning(block.getStatements(), attempt, "try");
		if (!attempt.getResources().isEmpty()) {
			return withResources(attempt, handler, returns);
		}
		BlockTree finallyBlock = attempt.getFinallyBlock();
		if (finallyBlock != null) {
			return tryFinally(attempt, body.block(block), returns, handler, body.block(finallyBlock));
		}
		if (handler == null) {
			throw new IllegalStateException("a try without catch, finally or resources");
		}
		VariableTree parameter = handler.getParameter();
		Expression pcall = call(name("pcall"),
				new Expression.FunctionExpression(FunctionBody.of(List.of(), body.block(block))));
		String ok = body.invent("ok", attempt);
		Element error = unit.element(parameter);
		String errorName = body.declare(parameter);
		body.caughtErrors.add(error);
		Block caught = body.block(handler.getBlock());
		if (!returns) {
			// The error value is named only when the catch block reads it
			List<String> names = body.uses(handler.getBlock(), error) ? List.of(ok, errorName) : List.of(ok);
			return List.of(
					Block.Entry.of(new Statement.Local(names, List.of(pcall))),
					Block.Entry.of(Statement.ifThen(new Expression.Unary(UnaryOperator.NOT, name(ok)), caught)));
		}
		boolean value = body.inConstructor || returnsValue(block);
		String result = value ? body.invent("result", attempt) : errorName;
		List<Block.Entry> otherwise = new ArrayList<>();
		if (value && body.uses(handler.getBlock(), error)) {
			// The second value is the result when pcall succeeded and the error when it did not
			otherwise.add(Block.Entry.of(new Statement.Local(List.of(errorName), List.of(name(result)))));
		}
		otherwise.addAll(caught.entries());
		Block succeeded = Block.of(new Statement.Return(value ? List.of(name(result)) : List.of()));
		return List.of(
				Block.Entry.of(new Statement.Local(List.of(ok, result), List.of(pcall))),
				Block.Entry.of(new Statement.If(List.of(new Statement.If.Branch(name(ok), succeeded)),
						new Block(otherwise, caught.closingComments()))));
	}

	/**
	 * Whether a block's every path returns, which a function pcall runs must tell apart from running to its end;
	 * false when no path returns. A block where some paths return and some do not is rejected.
	 */
	private boolean completesByReturning(List<? extends StatementTree> statements, Tree where, String block) {
		boolean returns = statements.stream().anyMatch(Exceptions::containsReturn);
		if (returns && Switches.canCompleteNormally(statements)) {
			throw new Rejection(where, "In Lua the " + block + " block is a function that pcall runs, and Lua cannot"
					+ " tell a function that returned nothing from one that ran to its end; make every path"
					+ " through the " + block + " block return, or none.");
		}
		return returns;
	}

	/**
	 * {@code try ... finally}: the try block, and a catch block, each in a function that {@code pcall} runs, so that
	 * the finally block runs whatever they do; then an error neither handled is raised again, unchanged, and a value
	 * they returned is returned.
	 *
	 * <pre>{@code
	 * local ok, result = pcall(function()
	 *     ...
	 * end)
	 * if not ok then
	 *     ok, result = pcall(function(e)
	 *         ...
	 *     end, result)
	 * end
	 * -- the finally block
	 * if not ok then
	 *     error(result, 0)
	 * end
	 * return result
	 * }</pre>
	 */
	private List<Block.Entry> tryFinally(Tree attempt, Block tryBody, boolean tryReturns, @Nullable CatchTree handler,
			Block finallyBody) {
		boolean catchReturns = false;
		Block caught = null;
		String errorName = null;
		if (handler != null) {
			checkStaysInside(handler.getBlock());
			catchReturns = completesByReturning(handler.getBlock().getStatements(), handler, "catch");
			errorName = body.declare(handler.getParameter());
			body.caughtErrors.add(unit.element(handler.getParameter()));
			caught = body.block(handler.getBlock());
		}
		boolean returns = tryReturns || catchReturns;
		boolean value = returns && (body.inConstructor || returnsValue(attempt));
		String ok = body.invent("ok", attempt);
		String result = body.invent(returns ? "result" : "failure", attempt);
		Expression failed = new Expression.Unary(UnaryOperator.NOT, name(ok));
		Expression tryFunction = new Expression.FunctionExpression(FunctionBody.of(List.of(), tryBody));
		Statement attempted = new Statement.Local(List.of(ok, result), List.of(call(name("pcall"), tryFunction)));
		List<Block.Entry> lowered = new ArrayList<>();
		lowered.add(Block.Entry.of(attempted));
		// Which block's value to return when only one of them returns
		String returned = null;
		if (caught != null && tryReturns != catchReturns) {
			returned = body.invent("returned", attempt);
			lowered.add(Block.Entry.of(new Statement.Local(List.of(returned),
					List.of(tryReturns ? name(ok) : new Expression.BooleanLiteral(false)))));
		}
		if (caught != null && handler != null) {
			List<Statement> handling = new ArrayList<>();
			// The catch block's function takes the error only when it reads it
			boolean readsError = body.uses(handler.getBlock(), unit.element(handler.getParameter()));
			Expression catchFunction = new Expression.FunctionExpression(
					FunctionBody.of(readsError ? List.of(errorName) : List.of(), caught));
			Expression handled = readsError
					? call(name("pcall"), catchFunction, name(result))
					: call(name("pcall"), catchFunction);
			handling.add(new Statement.Assignment(List.of(name(ok), name(result)), List.of(handled)));
			if (returned != null && catchReturns) {
				handling.add(Statement.assignment(name(returned), name(ok)));
			}
			lowered.add(Block.Entry.of(Statement.ifThen(failed, BodyLowering.blockOf(handling))));
		}
		// The finally block's statements keep their comments; the comments after its last one lead what follows
		lowered.addAll(finallyBody.entries());
		Statement raise = new Statement.CallStatement(call(name("error"), name(result), integer(0)));
		lowered.add(new Block.Entry(Statement.ifThen(failed, Block.of(raise)),
				new Trivia(false, finallyBody.closingComments(), null), null));
		Statement giveBack = new Statement.Return(value ? List.of(name(result)) : List.of());
		if (returned != null) {
			lowered.add(Block.Entry.of(Statement.ifThen(name(returned), Block.of(giveBack))));
		} else if (returns) {
			lowered.add(Block.Entry.of(giveBack));
		}
		return lowered;
	}

	/**
	 * {@code try (Reader r = open()) { ... }}: the resources declared first, then the try block, with the resources
	 * closed after it, in the order Java closes them, as a finally block that runs before any catch or finally of
	 * the statement's own.
	 */
	private List<Block.Entry> withResources(TryTree attempt, @Nullable CatchTree handler, boolean returns) {
		List<Block.Entry> declared = new ArrayList<>();
		List<Statement> closes = new ArrayList<>();
		for (Tree resource : attempt.getResources()) {
			Expression closed;
			TypeMirror type;
			if (resource instanceof VariableTree variable) {
				body.localVariable(variable).forEach(statement -> declared.add(Block.Entry.of(statement)));
				closed = name(unit.codeLocalName(unit.element(variable), variable));
				type = unit.type(variable);
			} else {
				closed = body.expression((ExpressionTree) resource);
				type = unit.type(resource);
			}
			if (types.pluginType(type) == null) {
				throw new Rejection(resource, "Lua has none of " + type + "'s Java code to close it with; use a"
						+ " resource class of the plugin's, which declares close().");
			}
			closes.addFirst(new Statement.CallStatement(new Expression.MethodCall(closed, "close", List.of())));
		}
		List<Block.Entry> inner = new ArrayList<>(declared);
		inner.addAll(tryFinally(attempt, body.block(attempt.getBlock()), returns, null,
				Block.of(closes.toArray(Statement[]::new))));
		BlockTree finallyBlock = attempt.getFinallyBlock();
		if (handler == null && finallyBlock == null) {
			return inner;
		}
		// A catch or finally of the statement's own sees the resources closed already
		Block innerBlock = new Block(inner, List.of());
		return tryFinally(attempt, innerBlock, returns, handler,
				finallyBlock == null ? Block.of() : body.block(finallyBlock));
	}

	/** {@code RuntimeException}, {@code Exception} and {@code Throwable}: the classes that catch every Lua error. */
	private boolean isCatchAll(TypeMirror type) {
		return types.is(type, "java.lang.RuntimeException") || types.is(type, "java.lang.Exception")
				|| types.is(type, "java.lang.Throwable");
	}

	/** Whether the tree is the parameter of a catch clause being lowered, the error value {@code pcall} handed back. */
	private boolean isCaughtError(ExpressionTree tree) {
		return tree instanceof IdentifierTree identifier && body.caughtErrors.contains(unit.element(identifier));
	}

	/** {@code e.getMessage()} is the error value itself, and {@code e.toString()} the value as text. */
	Expression caughtErrorMethod(MethodInvocationTree call, ExecutableElement method, ExpressionTree error) {
		return switch (method.getSimpleName().toString()) {
			case "getMessage", "getLocalizedMessage" -> body.expression(error);
			case "toString" -> call(name("tostring"), body.expression(error));
			default -> throw new Rejection(call, "A Lua error is only its value; read it with "
					+ error + ".getMessage().");
		};
	}

	/** A break, continue or yield that would leave the try block cannot leave the function pcall runs. */
	private static void checkStaysInside(BlockTree block) {
		List<Tree> found = new ArrayList<>();
		new TreeScanner<Void, Integer>() {
			@Override
			public Void visitBreak(BreakTree node, Integer depth) {
				if (depth == 0) {
					found.add(node);
				}
				return null;
			}

			@Override
			public Void visitContinue(ContinueTree node, Integer depth) {
				if (depth == 0) {
					found.add(node);
				}
				return null;
			}

			@Override
			public Void visitYield(YieldTree node, Integer depth) {
				found.add(node);
				return null;
			}

			// A break or continue inside these stays inside them
			@Override
			public Void visitWhileLoop(WhileLoopTree node, Integer depth) {
				return super.visitWhileLoop(node, depth + 1);
			}

			@Override
			public Void visitDoWhileLoop(DoWhileLoopTree node, Integer depth) {
				return super.visitDoWhileLoop(node, depth + 1);
			}

			@Override
			public Void visitForLoop(ForLoopTree node, Integer depth) {
				return super.visitForLoop(node, depth + 1);
			}

			@Override
			public Void visitEnhancedForLoop(EnhancedForLoopTree node, Integer depth) {
				return super.visitEnhancedForLoop(node, depth + 1);
			}

			@Override
			public Void visitSwitch(SwitchTree node, Integer depth) {
				return super.visitSwitch(node, depth + 1);
			}

			@Override
			public Void visitSwitchExpression(SwitchExpressionTree node, Integer depth) {
				return null;
			}

			@Override
			public Void visitLambdaExpression(LambdaExpressionTree node, Integer depth) {
				return null;
			}

			@Override
			public Void visitClass(ClassTree node, Integer depth) {
				return null;
			}
		}.scan(block, 0);
		if (!found.isEmpty()) {
			throw new Rejection(found.getFirst(), "Lua runs the try block as a function, which a break, continue or"
					+ " yield cannot leave; set a flag and act on it after the try.");
		}
	}

	/** Whether the block has a return of its own, not one inside a lambda or class. */
	private static boolean containsReturn(Tree block) {
		return !returns(block).isEmpty();
	}

	/** Whether a return of the block, not one inside a lambda or class, returns a value. */
	private static boolean returnsValue(Tree block) {
		return returns(block).stream().anyMatch(returned -> returned.getExpression() != null);
	}

	private static List<ReturnTree> returns(Tree block) {
		List<ReturnTree> found = new ArrayList<>();
		new TreeScanner<Void, Void>() {
			@Override
			public Void visitReturn(ReturnTree node, Void unused) {
				found.add(node);
				return null;
			}

			@Override
			public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
				return null;
			}

			@Override
			public Void visitClass(ClassTree node, Void unused) {
				return null;
			}
		}.scan(block, null);
		return found;
	}
}
