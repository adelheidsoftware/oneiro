package org.adelheid.oneiro.lowering.client;

import static org.adelheid.oneiro.lua.Expression.binary;
import static org.adelheid.oneiro.lua.Expression.name;

import java.util.List;
import org.adelheid.oneiro.lua.BinaryOperator;
import org.adelheid.oneiro.lua.Block;
import org.adelheid.oneiro.lua.Comment;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.Statement;
import org.jspecify.annotations.Nullable;

/**
 * The functions of a settings module, over the client's {@code PersistentDB}: a getter reads a setting with the typed
 * accessor of its kind and gives the default where nothing is stored, and a setter stores it. Each setting is kept
 * under {@code "<group>.<keyName>"}.
 */
public final class SettingsFunctions {

	/** How {@code PersistentDB} keeps a setting: the suffix of its typed accessors, {@code GetBool} and the others. */
	public enum Kind {
		BOOL("Bool"),
		INT("Int"),
		FLOAT("Float"),
		STRING("String");

		private final String suffix;

		Kind(String suffix) {
			this.suffix = suffix;
		}
	}

	private SettingsFunctions() {
	}

	/** The line a settings module's header ends with, saying where its settings are kept. */
	public static Comment storageNote(String group) {
		return Comment.line("Each setting is kept in " + ClientApi.PERSISTENT_DB + " under \"" + group
				+ ".\" and its key.");
	}

	/** The comment above a setting's getter or setter: its name, and its description when it has one. */
	public static Comment itemComment(String name, String description) {
		return Comment.line(description.isEmpty() ? name : name + ": " + description);
	}

	/**
	 * A getter's body: the stored value, or the default where none is stored. A boolean defaulting to anything but
	 * {@code false} needs an {@code if}, because {@code or} would turn a stored {@code false} into the default.
	 *
	 * @param enumTable the table of the plugin enum whose constants the setting holds by name, or null
	 */
	public static Block getter(String key, Kind kind, Expression fallback, @Nullable Expression enumTable) {
		Expression stored =
				Expression.methodCall(name(ClientApi.PERSISTENT_DB), "Get" + kind.suffix, Expression.string(key));
		if (enumTable != null) {
			// Only a name that is one of the enum's constants reads back as a value
			Expression constant = new Expression.Index(enumTable, stored);
			return Block.of(new Statement.Return(List.of(binary(constant, BinaryOperator.OR, fallback))));
		}
		boolean falseDefault = fallback instanceof Expression.BooleanLiteral literal && !literal.value();
		if (kind != Kind.BOOL || falseDefault) {
			return Block.of(new Statement.Return(List.of(binary(stored, BinaryOperator.OR, fallback))));
		}
		Expression unset = binary(name("stored"), BinaryOperator.EQUAL, new Expression.Nil());
		return Block.of(
				new Statement.Local(List.of("stored"), List.of(stored)),
				Statement.ifThen(unset, Block.of(new Statement.Return(List.of(fallback)))),
				new Statement.Return(List.of(name("stored"))));
	}

	/** A setter's body, which stores the value with the typed accessor of the setting's kind. */
	public static Block setter(String key, Kind kind, Expression value) {
		Expression store = Expression.methodCall(name(ClientApi.PERSISTENT_DB), "Set" + kind.suffix,
				Expression.string(key), value);
		return Block.of(new Statement.CallStatement(store));
	}
}
