package org.adelheid.oneiro.lowering;

import static org.adelheid.oneiro.lua.Expression.binary;
import static org.adelheid.oneiro.lua.Expression.call;
import static org.adelheid.oneiro.lua.Expression.field;
import static org.adelheid.oneiro.lua.Expression.name;

import java.util.ArrayList;
import java.util.List;
import org.adelheid.oneiro.lua.BinaryOperator;
import org.adelheid.oneiro.lua.Block;
import org.adelheid.oneiro.lua.Comment;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.FunctionBody;
import org.adelheid.oneiro.lua.SourcePosition;
import org.adelheid.oneiro.lua.Statement;
import org.adelheid.oneiro.lua.Trivia;
import org.jspecify.annotations.Nullable;

/**
 * The statements with which {@code main.lua} subscribes the plugin class's {@code @Subscribe} methods, and the global
 * {@code PluginShutdown}, which the client calls when the plugin is disabled: it unsubscribes them, then runs
 * {@code shutDown()}. The dev source set's {@code dev/main.lua} does the same, and its {@code PluginShutdown} runs the
 * plugin's own first.
 */
public final class PluginLifecycle {

	private PluginLifecycle() {
	}

	/** {@code Event.Name.Subscribe("<id>.<method>", method)}. */
	public static Statement subscribe(String event, String reference, String function) {
		return new Statement.CallStatement(call(field(field(name(ClientApi.EVENT), event), "Subscribe"),
				Expression.string(reference), name(function)));
	}

	/** {@code Event.Name.Unsubscribe("<id>.<method>")}. */
	public static Statement unsubscribe(String event, String reference) {
		return new Statement.CallStatement(call(field(field(name(ClientApi.EVENT), event), "Unsubscribe"),
				Expression.string(reference)));
	}

	/**
	 * The global {@code PluginShutdown}, with the comment that says why it is global. In the dev source set it runs
	 * the plugin's own shutdown first, which a local keeps before this one replaces it.
	 *
	 * @param body the unsubscriptions, then the statements of {@code shutDown()}
	 * @param closing the comments that close {@code shutDown()}'s body
	 * @param origin the line of {@code shutDown()}, or null when the class has none
	 */
	public static List<Block.Entry> shutdown(List<Block.Entry> body, List<Comment> closing, boolean dev,
			@Nullable SourcePosition origin) {
		List<Block.Entry> statements = new ArrayList<>();
		if (dev) {
			Expression defined = binary(name("pluginShutdown"), BinaryOperator.NOT_EQUAL, new Expression.Nil());
			Statement shutDownFirst = new Statement.CallStatement(call(name("pluginShutdown")));
			statements.add(Block.Entry.of(Statement.ifThen(defined, Block.of(shutDownFirst))));
		}
		statements.addAll(body);
		Statement function = new Statement.FunctionDeclaration(List.of(ClientApi.PLUGIN_SHUTDOWN), null,
				FunctionBody.of(List.of(), new Block(statements, closing)));
		List<Block.Entry> entries = new ArrayList<>();
		if (dev) {
			Statement saved = new Statement.Local(List.of("pluginShutdown"), List.of(name(ClientApi.PLUGIN_SHUTDOWN)));
			Comment note = Comment.line("The plugin's own shutdown, which runs first.");
			entries.add(new Block.Entry(saved, new Trivia(true, List.of(note), null), null));
		}
		Comment global = Comment.line("The client calls " + ClientApi.PLUGIN_SHUTDOWN
				+ " when the plugin is disabled, and requires it to be global.");
		entries.add(new Block.Entry(function, new Trivia(!dev, List.of(global), null), origin));
		return entries;
	}
}
