package org.adelheid.oneiro.java;

import static org.adelheid.oneiro.lua.Expression.binary;
import static org.adelheid.oneiro.lua.Expression.call;
import static org.adelheid.oneiro.lua.Expression.field;
import static org.adelheid.oneiro.lua.Expression.name;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import org.adelheid.oneiro.lua.BinaryOperator;
import org.adelheid.oneiro.lua.Block;
import org.adelheid.oneiro.lua.Comment;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.FunctionBody;
import org.adelheid.oneiro.lua.Statement;
import org.adelheid.oneiro.lua.Trivia;
import org.jspecify.annotations.Nullable;

/**
 * The plugin's entry class, the one that extends {@code Plugin}, as main.lua, and the dev source set's
 * {@code DevPlugin} as dev/main.lua: the body of {@code startUp()} runs when the file loads, each
 * {@code @Subscribe} method subscribes to its event, and the global {@code PluginShutdown} unsubscribes them and
 * runs {@code shutDown()}.
 */
final class PluginEntry {

	private final UnitLowering unit;
	private final JavaTypes types;

	PluginEntry(UnitLowering unit) {
		this.unit = unit;
		this.types = unit.types;
	}

	/** A {@code @Subscribe} method of the plugin class: the event it subscribes to and its local function. */
	record Subscription(MethodTree method, String event, String local) {
	}

	/**
	 * The plugin class's {@code @Subscribe} methods, each checked: one parameter, an event's payload, which names the
	 * event; the return type its callbacks have; and the permission or manifest flag the event needs. A method whose
	 * event passes nothing gets a Lua function without parameters.
	 */
	List<Subscription> subscriptions(ClassTree type) {
		List<Subscription> found = new ArrayList<>();
		for (Tree member : type.getMembers()) {
			if (!(member instanceof MethodTree method) || !JavaTypes.isSubscriber(unit.element(method))) {
				continue;
			}
			ExecutableElement element = (ExecutableElement) unit.element(method);
			try {
				if (method.getParameters().size() != 1) {
					throw new Rejection(method, "A @Subscribe method takes one parameter, the payload of the event it"
							+ " subscribes to, such as InventoryUpdatedEventPayload.");
				}
				VariableTree parameter = method.getParameters().getFirst();
				TypeMirror payloadType = unit.type(parameter);
				TypeElement payload = payloadType instanceof DeclaredType declared
						&& declared.asElement() instanceof TypeElement named ? named : null;
				JavaTypes.Event event = payload == null ? null : types.event(payload);
				if (payload == null || event == null) {
					throw new Rejection(parameter, (payload == null ? payloadType : payload.getSimpleName())
							+ " is not an event's payload; the parameter of a @Subscribe method names the event, such"
							+ " as InventoryUpdatedEventPayload for InventoryUpdated.");
				}
				TypeKind returned = element.getReturnType().getKind();
				if (event.returnsBoolean() && returned != TypeKind.BOOLEAN) {
					throw new Rejection(method, event.name() + "'s callbacks return a boolean the client acts on;"
							+ " declare " + method.getName() + " to return boolean.");
				}
				if (!event.returnsBoolean() && returned != TypeKind.VOID) {
					throw new Rejection(method, event.name() + "'s callbacks return nothing; declare "
							+ method.getName() + " void.");
				}
				checkEventAllowed(payload, event, method);
				if (ElementFilter.fieldsIn(payload.getEnclosedElements()).isEmpty()) {
					if (usesParameter(method, unit.element(parameter))) {
						throw new Rejection(parameter, "The " + event.name() + " event passes nothing, so its payload"
								+ " has nothing to read; leave " + parameter.getName() + " unused.");
					}
					unit.parameterless.add(element);
				}
				found.add(new Subscription(method, event.name(), unit.names.luaName(method.getName().toString())));
			} catch (Rejection rejection) {
				unit.reject(rejection);
			}
		}
		return found;
	}

	/** An event that needs a permission or a manifest flag fails to compile unless the build declares it. */
	private void checkEventAllowed(TypeElement payload, JavaTypes.Event event, Tree where) {
		String permission = types.inherited(payload, JavaTypes.LUA_PERMISSION);
		if (permission != null && !unit.plugin.permissions().contains(permission)) {
			throw new Rejection(where, "The " + event.name() + " event needs the " + permission + " permission, which"
					+ " the manifest does not declare; add it to the build, oneiro { plugin { permissions { optional("
					+ permission + ") } } }.");
		}
		String flag = types.inherited(payload, JavaTypes.LUA_MANIFEST_FLAG);
		if (flag != null && !unit.plugin.manifestFlags().contains(flag)) {
			String setting = flag.startsWith("has") ? Character.toLowerCase(flag.charAt(3)) + flag.substring(4) : flag;
			throw new Rejection(where, "The " + event.name() + " event fires only when the manifest sets " + flag
					+ "; set " + setting + " = true in the build's oneiro { plugin { } } block.");
		}
	}

	private boolean usesParameter(MethodTree method, Element parameter) {
		Boolean found = new TreePathScanner<Boolean, Void>() {
			@Override
			public Boolean visitIdentifier(IdentifierTree node, Void unused) {
				return parameter.equals(unit.trees.getElement(getCurrentPath()));
			}

			@Override
			public Boolean reduce(Boolean first, Boolean second) {
				return Boolean.TRUE.equals(first) || Boolean.TRUE.equals(second);
			}
		}.scan(new TreePath(unit.path(method), method.getBody()), null);
		return Boolean.TRUE.equals(found);
	}

	/** Only the plugin class has {@code @Subscribe} methods, because only main.lua runs when the plugin starts. */
	void checkNoSubscribers(ClassTree type) {
		new TreeScanner<Void, Void>() {
			@Override
			public Void visitMethod(MethodTree node, Void unused) {
				if (JavaTypes.isSubscriber(unit.element(node))) {
					unit.error(unit.start(node), "Only the plugin class's methods are subscribed with @Subscribe;"
							+ " subscribe this one with Event.<Name>.Subscribe(name, callback), and unsubscribe it when"
							+ " it is done.");
				}
				return null;
			}
		}.scan(type, null);
	}

	/**
	 * What the plugin class adds to main.lua after its members: the body of {@code startUp()}, which runs when the file
	 * loads, then a subscription for each {@code @Subscribe} method, then the global {@code PluginShutdown}, which the
	 * client calls when the plugin is disabled: it unsubscribes each method, then runs {@code shutDown()}. The dev
	 * source set's {@code DevPlugin} class does the same in its module, and its {@code PluginShutdown} runs the
	 * plugin's first.
	 */
	List<UnitLowering.Member> entryLifecycle(ClassTree type, List<Subscription> subscriptions) {
		List<UnitLowering.Member> members = new ArrayList<>();
		MethodTree startUp = lifecycleMethod(type, "startUp");
		MethodTree shutDown = lifecycleMethod(type, "shutDown");
		boolean dev = unit.entry != null && types.isDevEntry(unit.entry);
		String prefix = unit.plugin.id() + (dev ? ".dev." : ".");
		if (startUp != null) {
			// Start-up code stands apart from the functions above it
			List<Block.Entry> entries = new ArrayList<>(lifecycleBody(startUp).entries());
			if (!entries.isEmpty()) {
				Block.Entry first = entries.getFirst();
				entries.set(0, new Block.Entry(first.statement(),
						new Trivia(true, first.trivia().leading(), first.trivia().trailing()), first.origin()));
			}
			members.add(new UnitLowering.Member(List.of(unit.element(startUp)), false, null, entries, List.of(),
					unit.assembly().references(startUp), Set.of()));
		}
		if (!subscriptions.isEmpty()) {
			List<Block.Entry> entries = new ArrayList<>();
			Set<Element> referenced = new HashSet<>();
			for (Subscription subscription : subscriptions) {
				entries.add(new Block.Entry(new Statement.CallStatement(call(
						eventMember(subscription.event(), "Subscribe"),
						Expression.string(prefix + subscription.method().getName()), name(subscription.local()))),
						new Trivia(entries.isEmpty(), List.of(), null), unit.origin(subscription.method())));
				referenced.add(unit.element(subscription.method()));
			}
			members.add(new UnitLowering.Member(List.of(), false, null, entries, List.of(), referenced, Set.of()));
		}
		if (!subscriptions.isEmpty() || shutDown != null || dev) {
			List<Block.Entry> body = new ArrayList<>();
			if (dev) {
				body.add(Block.Entry.of(new Statement.If(List.of(new Statement.If.Branch(
						binary(name("pluginShutdown"), BinaryOperator.NOT_EQUAL, new Expression.Nil()),
						Block.of(new Statement.CallStatement(call(name("pluginShutdown")))))), null)));
			}
			for (Subscription subscription : subscriptions) {
				body.add(Block.Entry.of(new Statement.CallStatement(call(eventMember(subscription.event(),
						"Unsubscribe"), Expression.string(prefix + subscription.method().getName())))));
			}
			List<Comment> closing = List.of();
			Set<Element> referenced = new HashSet<>();
			if (shutDown != null) {
				Block lowered = lifecycleBody(shutDown);
				body.addAll(lowered.entries());
				closing = lowered.closingComments();
				referenced.addAll(unit.assembly().references(shutDown));
			}
			Statement function = new Statement.FunctionDeclaration(List.of("PluginShutdown"), null,
					FunctionBody.of(List.of(), new Block(body, closing)));
			List<Block.Entry> entries = new ArrayList<>();
			if (dev) {
				entries.add(new Block.Entry(new Statement.Local(List.of("pluginShutdown"),
						List.of(name("PluginShutdown"))), new Trivia(true, List.of(Comment.line("The plugin's own"
						+ " shutdown, which runs first.")), null), null));
			}
			entries.add(new Block.Entry(function, new Trivia(!dev, List.of(Comment.line("The client calls"
					+ " PluginShutdown when the plugin is disabled, and requires it to be global.")), null),
					shutDown == null ? null : unit.origin(shutDown)));
			members.add(new UnitLowering.Member(shutDown == null
					? List.of()
					: List.of(unit.element(shutDown)), true, null, entries, List.of(), referenced, Set.of()));
		}
		return members;
	}

	/** {@code Event.Name.Subscribe}, a member of an event's subscription. */
	private Expression eventMember(String event, String member) {
		unit.useGlobal("Event");
		return field(field(name("Event"), event), member);
	}

	private static @Nullable MethodTree lifecycleMethod(ClassTree type, String name) {
		for (Tree member : type.getMembers()) {
			if (member instanceof MethodTree method && UnitLowering.isLifecycle(method)
					&& method.getName().contentEquals(name)) {
				return method;
			}
		}
		return null;
	}

	/** A lifecycle method's statements, with the comments above the method above them. */
	private Block lifecycleBody(MethodTree method) {
		BlockTree body = method.getBody();
		if (body == null) {
			throw new IllegalStateException("a lifecycle method without a body: " + method.getName());
		}
		Block lowered = new BodyLowering(unit, false, false).block(body);
		List<Comment> above = new ArrayList<>(BodyLowering.comments(
				unit.lifecycleComments.getOrDefault(method.getName().toString(), List.of())));
		DocCommentTree doc = unit.trees.getDocCommentTree(unit.path(method));
		if (doc != null) {
			DocText.lines(doc.getFullBody()).forEach(line -> above.add(Comment.line(line)));
		}
		if (above.isEmpty() || lowered.entries().isEmpty()) {
			if (!above.isEmpty()) {
				unit.memberComments().dropComments(unit.lifecycleComments.getOrDefault(method.getName().toString(),
						List.of()), "This comment is on an empty " + method.getName()
						+ "(), which has no Lua, so it was left out.");
			}
			return lowered;
		}
		return new Block(MemberComments.withFirstComments(lowered.entries(), above), lowered.closingComments());
	}

	/**
	 * A plugin has one class that extends {@code Plugin}, in the main source set, and at most one that extends {@code
	 * DevPlugin}, in the dev source set: each becomes a file of its own, {@code main.lua} or {@code dev/main.lua}.
	 */
	void checkEntry(ClassTree type, TypeElement entryType) {
		boolean dev = types.isDevEntry(entryType);
		if (dev && !unit.plugin.dev()) {
			unit.error(unit.start(type), entryType.getSimpleName() + " extends DevPlugin, whose code must never ship;"
					+ " move it into the dev source set, src/dev/java.");
		} else if (!dev && unit.plugin.dev()) {
			unit.error(unit.start(type), entryType.getSimpleName() + " extends Plugin, so it becomes the main.lua that"
					+ " ships; keep it in src/main/java, and extend DevPlugin in the dev source set instead.");
		}
		List<String> others = types.otherEntries(entryType);
		if (!others.isEmpty()) {
			unit.error(unit.start(type), "Only one class extends " + (dev ? "DevPlugin" : "Plugin")
					+ ", because it becomes " + (dev ? "dev/main.lua" : "main.lua") + "; "
					+ String.join(" and ", others) + (others.size() == 1 ? " does too." : " do too."));
		}
	}
}
