package org.adelheid.oneiro.lowering.translation;

import static org.adelheid.oneiro.lua.Expression.binary;
import static org.adelheid.oneiro.lua.Expression.call;
import static org.adelheid.oneiro.lua.Expression.field;
import static org.adelheid.oneiro.lua.Expression.integer;
import static org.adelheid.oneiro.lua.Expression.methodCall;
import static org.adelheid.oneiro.lua.Expression.string;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.adelheid.oneiro.lowering.LuaModule;
import org.adelheid.oneiro.lowering.ModulePaths;
import org.adelheid.oneiro.lowering.SourceLanguage;
import org.adelheid.oneiro.lua.BinaryOperator;
import org.adelheid.oneiro.lua.Block;
import org.adelheid.oneiro.lua.Comment;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.FunctionBody;
import org.adelheid.oneiro.lua.Statement;
import org.adelheid.oneiro.lua.Trivia;
import org.adelheid.oneiro.lua.UnaryOperator;

/**
 * Small Lua functions for translations that no single expression can say, such as Java's integer division. Each helper
 * the plugin uses is a file of its own under {@code utils/}, {@code utils/ordered_table.lua}, whose comment names the
 * behaviour of the plugin's language that it reproduces, and which the modules that use it require. A few serve
 * functions only Kotlin has, such as {@code padStart}.
 */
public enum Helper {

	QUOTIENT("quotient", "Integer division that rounds toward zero, as Java's / does; Lua's // rounds down."),
	TO_INTEGER("toInteger", "Java's (int) cast of a double: drops the fraction, rounding toward zero."),
	FILLED("filled", "A new array of n copies of value, as Java fills a new array with zeros or false."),
	LIST_CONTAINS("listContains", "Whether the list holds the value, as Java's List.contains."),
	LIST_INDEX_OF("listIndexOf", "The 0-based position of the value in the list, or -1, as Java's List.indexOf."),
	STRING_INDEX_OF("stringIndexOf",
			"The 0-based position of the text in the string, or -1, as Java's String.indexOf."),
	ENDS_WITH("endsWith", "Whether the string ends with the suffix, as Java's String.endsWith."),
	TRIM("trim", "The string without leading and trailing whitespace, as Java's String.trim."),
	TABLE_LENGTH("tableLength", "The number of entries in a map or set, as Java's size(); a Lua table keeps no count."),
	SPLIT("split", "The parts of the text between the separators, as Java's String.split with a plain separator: with"
			+ " no limit, trailing empty parts are dropped; a negative limit keeps them; a positive one makes at most"
			+ " that many parts.", "text, separator, limit", """
			if limit == nil then
			    limit = 0
			end
			local parts = {}
			local start = 1
			while limit <= 0 or #parts < limit - 1 do
			    local first, last = text:find(separator, start, true)
			    if first == nil then
			        break
			    end
			    parts[#parts + 1] = text:sub(start, first - 1)
			    start = last + 1
			end
			if #parts == 0 then
			    return { text }
			end
			parts[#parts + 1] = text:sub(start)
			if limit == 0 then
			    while parts[#parts] == "" do
			        parts[#parts] = nil
			    end
			end
			return parts"""),
	VALUE_OR("valueOr", "The value, or the default when it is nil, as Java's getOrDefault does; Lua's or would also"
			+ " replace a stored false.", "value, default", """
			if value == nil then
			    return default
			end
			return value"""),
	COMPUTE_IF_ABSENT("computeIfAbsent", "The value under the key, computed and stored first when there is none, as"
			+ " Java's Map.computeIfAbsent.", "map, key, compute", """
			local value = map[key]
			if value == nil then
			    value = compute(key)
			    map[key] = value
			end
			return value"""),
	MERGE("merge", "Stores the value under the key, or the stored value combined with it when there is one, as Java's"
			+ " Map.merge; a nil result removes the key.", "map, key, value, combine", """
			local old = map[key]
			if old == nil then
			    map[key] = value
			else
			    map[key] = combine(old, value)
			end
			return map[key]"""),
	COMPUTE("compute", "Stores what the function makes of the key and its value, nil for none, as Java's Map.compute;"
			+ " a nil result removes the key.", "map, key, remap", """
			map[key] = remap(key, map[key])
			return map[key]"""),
	COMPUTE_IF_PRESENT("computeIfPresent", "As compute, but only for a key that has a value, as Java's"
			+ " Map.computeIfPresent.", "map, key, remap", """
			if map[key] ~= nil then
			    map[key] = remap(key, map[key])
			end
			return map[key]"""),
	BUILDER_SET("builderSet", "Makes a StringBuilder, which is a list of pieces, hold only the text.",
			"builder, text", """
			for i = #builder, 1, -1 do
			    builder[i] = nil
			end
			builder[1] = text"""),
	LIST_TEXT("listText", "The list as Java writes it: [a, b, c].", "list", """
			local parts = {}
			for i, value in ipairs(list) do
			    parts[i] = tostring(value)
			end
			return "[" .. table.concat(parts, ", ") .. "]\""""),
	SET_TEXT("setText", "The set as Java writes it, [a, b], in Lua's order, which is no more fixed than a HashSet's.",
			"set", """
			local parts = {}
			for value in pairs(set) do
			    parts[#parts + 1] = tostring(value)
			end
			return "[" .. table.concat(parts, ", ") .. "]\""""),
	MAP_TEXT("mapText", "The map as Java writes it, {a=1, b=2}, in Lua's order, which is no more fixed than a"
			+ " HashMap's.", "map", """
			local parts = {}
			for key, value in pairs(map) do
			    parts[#parts + 1] = tostring(key) .. "=" .. tostring(value)
			end
			return "{" .. table.concat(parts, ", ") .. "}\""""),
	CHARACTERS("characters", "The text's characters as a list of one-character strings, as Java's toCharArray.",
			"text", """
			local characters = {}
			for i = 1, #text do
			    characters[i] = text:sub(i, i)
			end
			return characters"""),
	GRID("grid", "A new array of the sizes, each size an array of the next, with copies of value innermost, as Java"
			+ " fills a new array of more than one dimension.", "value, size, ...", """
			local array = {}
			for i = 1, size do
			    if select("#", ...) == 0 then
			        array[i] = value
			    else
			        array[i] = grid(value, ...)
			    end
			end
			return array"""),
	WITH_MEMBERS("withMembers", "Gives the object the members of an anonymous class, which replace its class's where"
			+ " they share a name, and returns it.", "object, members", """
			for name, member in pairs(members) do
			    object[name] = member
			end
			return object"""),
	IS_INSTANCE("isInstance", "Whether the value is an object of the class, or of a class that extends it, as Java's"
			+ " instanceof.", "value, class", """
			if type(value) ~= "table" then
			    return false
			end
			local current = getmetatable(value)
			while current ~= nil do
			    if current == class then
			        return true
			    end
			    -- A subclass's table looks up what it lacks in its superclass's
			    local parent = getmetatable(current)
			    current = parent and parent.__index
			end
			return false"""),
	ORDERED_TABLE("orderedTable", "A table whose pairs walks its keys in the order they were first added, as Java's"
			+ " LinkedHashMap and LinkedHashSet do. It holds its entries itself; its metatable keeps their order.", "",
			"""
			local keys = {}
			local added = {}
			return setmetatable({}, {
			    __newindex = function(t, key, value)
			        if value == nil then
			            return
			        end
			        if added[key] then
			            -- A key removed and added again goes to the end, as in a LinkedHashMap
			            for i = #keys, 1, -1 do
			                if keys[i] == key then
			                    table.remove(keys, i)
			                    break
			                end
			            end
			        end
			        added[key] = true
			        keys[#keys + 1] = key
			        rawset(t, key, value)
			    end,
			    __pairs = function(t)
			        local i = 0
			        return function()
			            repeat
			                i = i + 1
			                local key = keys[i]
			                -- A key removed since it was added is passed over
			                if key ~= nil and rawget(t, key) ~= nil then
			                    return key, rawget(t, key)
			                end
			            until key == nil
			        end
			    end,
			})"""),
	KEYS_OF("keysOf", "The table's keys as a list, in the order pairs gives them, as Java copies a set or a map's"
			+ " keySet() into a list.", "t", """
			local list = {}
			for key in pairs(t) do
			    list[#list + 1] = key
			end
			return list"""),
	VALUES_OF("valuesOf", "The table's values as a list, in the order pairs gives them, as Java copies a map's"
			+ " values() into a list.", "t", """
			local list = {}
			for _, value in pairs(t) do
			    list[#list + 1] = value
			end
			return list"""),
	ADD_ELEMENTS("addElements", "Adds each element of the list to the set, and gives the set.", "set, list", """
			for _, value in ipairs(list) do
			    set[value] = true
			end
			return set"""),
	ADD_KEYS("addKeys", "Adds each key of the table to the set, and gives the set.", "set, t", """
			for key in pairs(t) do
			    set[key] = true
			end
			return set"""),
	COPY_ENTRIES("copyEntries", "Puts each entry of the source into the map, and gives the map.", "map, source", """
			for key, value in pairs(source) do
			    map[key] = value
			end
			return map"""),
	PUT_ENTRIES("putEntries", "Puts the keys and values the list holds in turn into the map, in order, and gives the"
			+ " map.", "map, list", """
			for i = 1, #list, 2 do
			    map[list[i]] = list[i + 1]
			end
			return map"""),
	FIRST_KEY("firstKey", "The first key pairs gives, as a TreeMap's firstKey() or a TreeSet's first(); nil for an"
			+ " empty table, where Java throws.", "t", """
			for key in pairs(t) do
			    return key
			end
			return nil"""),
	LAST_KEY("lastKey", "The last key pairs gives, as a TreeMap's lastKey() or a TreeSet's last(); nil for an empty"
			+ " table, where Java throws.", "t", """
			local last = nil
			for key in pairs(t) do
			    last = key
			end
			return last"""),
	PAD_START("padStart", "The text after as many padChar as make it length long, as Kotlin's padStart; longer text"
			+ " stays as it is.", "text, length, padChar", """
			return padChar:rep(length - #text) .. text"""),
	PAD_END("padEnd", "The text before as many padChar as make it length long, as Kotlin's padEnd; longer text stays"
			+ " as it is.", "text, length, padChar", """
			return text .. padChar:rep(length - #text)"""),
	REMOVE_PREFIX("removePrefix", "The text without the prefix when it starts with it, as Kotlin's removePrefix.",
			"text, prefix", """
			if text:sub(1, #prefix) == prefix then
			    return text:sub(#prefix + 1)
			end
			return text"""),
	REMOVE_SUFFIX("removeSuffix", "The text without the suffix when it ends with it, as Kotlin's removeSuffix.",
			"text, suffix", """
			if suffix ~= "" and text:sub(-#suffix) == suffix then
			    return text:sub(1, -#suffix - 1)
			end
			return text"""),
	SUBSTRING_BEFORE("substringBefore", "The text before the first delimiter, or all of it when there is none, as"
			+ " Kotlin's substringBefore.", "text, delimiter", """
			local first = text:find(delimiter, 1, true)
			if first == nil then
			    return text
			end
			return text:sub(1, first - 1)"""),
	SUBSTRING_AFTER("substringAfter", "The text after the first delimiter, or all of it when there is none, as"
			+ " Kotlin's substringAfter.", "text, delimiter", """
			local _, last = text:find(delimiter, 1, true)
			if last == nil then
			    return text
			end
			return text:sub(last + 1)"""),
	REPLACE("replace", "The text with each occurrence of old replaced by new, found as it is written, not as a"
			+ " pattern, as Kotlin's replace.", "text, old, new", """
			if old == "" then
			    -- Kotlin puts new before each character and after the last
			    return (text:gsub("", (new:gsub("%%", "%%%%"))))
			end
			local parts = {}
			local start = 1
			while true do
			    local first, last = text:find(old, start, true)
			    if first == nil then
			        break
			    end
			    parts[#parts + 1] = text:sub(start, first - 1)
			    parts[#parts + 1] = new
			    start = last + 1
			end
			parts[#parts + 1] = text:sub(start)
			return table.concat(parts)"""),
	LINES("lines", "The text's lines, split at \\r\\n, \\n or \\r, as Kotlin's lines().", "text", """
			local list = {}
			local start = 1
			while true do
			    local first = text:find("[\\r\\n]", start)
			    if first == nil then
			        break
			    end
			    list[#list + 1] = text:sub(start, first - 1)
			    -- \\r\\n ends one line, not two
			    if text:sub(first, first + 1) == "\\r\\n" then
			        start = first + 2
			    else
			        start = first + 1
			    end
			end
			list[#list + 1] = text:sub(start)
			return list"""),
	COMPARE("compare", "-1, 0 or 1 as a comes before b, with it or after it, as Kotlin's compareTo, whose sign is all"
			+ " it promises.", "a, b", """
			if a < b then
			    return -1
			elseif b < a then
			    return 1
			end
			return 0"""),
	REVERSED("reversed", "A new list of the list's elements in the reverse order, as Kotlin's reversed().", "list", """
			local copy = {}
			for i = #list, 1, -1 do
			    copy[#copy + 1] = list[i]
			end
			return copy"""),
	CLEAR("clear", "Removes every element or entry of the table, as Kotlin's clear().", "t", """
			for key in pairs(t) do
			    t[key] = nil
			end"""),
	INTERSECTION("intersection", "Adds each element of a that b has too to the set, and gives the set, as Kotlin's"
			+ " intersect.", "set, a, b", """
			for key in pairs(a) do
			    if b[key] ~= nil then
			        set[key] = true
			    end
			end
			return set"""),
	DIFFERENCE("difference", "Adds each element of a that b lacks to the set, and gives the set, as Kotlin's"
			+ " subtract.", "set, a, b", """
			for key in pairs(a) do
			    if b[key] == nil then
			        set[key] = true
			    end
			end
			return set"""),
	SORTED_TABLE("sortedTable", "A table whose pairs walks its keys in sorted order, as Java's TreeMap and TreeSet"
			+ " do; comesBefore orders them, and Lua's < when it is nil.", "comesBefore", """
			return setmetatable({}, {
			    __pairs = function(t)
			        local keys = {}
			        for key in next, t do
			            keys[#keys + 1] = key
			        end
			        table.sort(keys, comesBefore)
			        local i = 0
			        return function()
			            repeat
			                i = i + 1
			                local key = keys[i]
			                -- A key removed since the walk began is passed over
			                if key ~= nil and rawget(t, key) ~= nil then
			                    return key, rawget(t, key)
			                end
			            until key == nil
			        end
			    end,
			})""");

	/** How long a comment line may be after its "-- ", to stay within 120 columns. */
	private static final int WIDTH = 117;

	private final String luaName;
	private final String comment;
	/**
	 * For a helper written as Lua: its parameters, comma-separated, and its body, whose lines are printed as they are.
	 */
	private final String parameters;
	private final String body;

	Helper(String luaName, String comment) {
		this(luaName, comment, "", "");
	}

	Helper(String luaName, String comment, String parameters, String body) {
		this.luaName = luaName;
		this.comment = comment;
		this.parameters = parameters;
		this.body = body;
	}

	public String luaName() {
		return luaName;
	}

	/** The require path of the helper's file, without {@code .lua}: {@code utils/ordered_table}. */
	public String requirePath() {
		return ModulePaths.helperPath(luaName);
	}

	/** The files of the helpers, each defining its helper and returning it; see {@link #module}. */
	public static List<LuaModule> modules(Collection<Helper> helpers, SourceLanguage language) {
		return helpers.stream().map(helper -> helper.module(language)).toList();
	}

	/**
	 * The helper's own file, which starts with its path and the comment naming the rule it follows, defines the helper,
	 * and returns it: {@code local function orderedTable() ... end} and {@code return orderedTable}.
	 */
	public LuaModule module(SourceLanguage language) {
		Block.Entry returned = new Block.Entry(new Statement.Return(List.of(Expression.name(luaName))),
				new Trivia(true, List.of(), null), null);
		return LuaModule.of(requirePath() + ".lua", comment(language),
				new Block(List.of(Block.Entry.of(definition()), returned), List.of()));
	}

	/**
	 * The comment above the helper, naming the rule of the plugin's language that it follows, wrapped to stay within
	 * 120 columns as a line comment at the top of a file.
	 */
	public List<Comment> comment(SourceLanguage language) {
		String text = language == SourceLanguage.KOTLIN ? kotlinComment() : comment;
		List<Comment> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ", -1)) {
			if (!line.isEmpty() && line.length() + 1 + word.length() > WIDTH) {
				lines.add(Comment.line(line.toString()));
				line.setLength(0);
			}
			line.append(line.isEmpty() ? "" : " ").append(word);
		}
		lines.add(Comment.line(line.toString()));
		return lines;
	}

	/**
	 * The comment for a Kotlin plugin's Lua. The Kotlin front end uses only the helpers listed here; reaching another
	 * one is a mistake in the front end, which the conformance cases catch.
	 */
	private String kotlinComment() {
		return switch (this) {
			case QUOTIENT -> "Integer division that rounds toward zero, as Kotlin's / does; Lua's // rounds down.";
			case TO_INTEGER -> "Kotlin's toInt() of a Double: drops the fraction, rounding toward zero.";
			case FILLED -> "A new array of n copies of value, as Kotlin fills a new IntArray with zeros or a"
					+ " BooleanArray with false.";
			case LIST_CONTAINS -> "Whether the list holds the value, as Kotlin's List.contains.";
			case LIST_INDEX_OF -> "The 0-based position of the value in the list, or -1, as Kotlin's List.indexOf.";
			case STRING_INDEX_OF -> "The 0-based position of the text in the string, or -1, as Kotlin's"
					+ " String.indexOf.";
			case ENDS_WITH -> "Whether the string ends with the suffix, as Kotlin's String.endsWith.";
			case TRIM -> "The string without leading and trailing whitespace, as Kotlin's String.trim.";
			case TABLE_LENGTH -> "The number of entries in a map or set, as Kotlin's size; a Lua table keeps no count.";
			case SPLIT -> "The parts of the text between the separators, as Kotlin's String.split with a plain"
					+ " separator, empty ones among them; the limit is -1 for any number of parts, or the most parts"
					+ " to make.";
			case VALUE_OR -> "The value, or the default when it is nil, as Kotlin's ?: does; Lua's or would also"
					+ " replace a false.";
			case COMPUTE_IF_ABSENT -> "The value under the key, computed and stored first when there is none, as"
					+ " Kotlin's getOrPut.";
			case LIST_TEXT -> "The list as Kotlin writes it: [a, b, c].";
			case SET_TEXT -> "The set as Kotlin writes it, [a, b], in the order pairs walks it.";
			case MAP_TEXT -> "The map as Kotlin writes it, {a=1, b=2}, in the order pairs walks it.";
			case CHARACTERS -> "The text's characters as a list of one-character strings, as Kotlin's toCharArray.";
			case GRID -> "What Kotlin's Array(rows) { IntArray(columns) } makes: a new array of the sizes, each size an"
					+ " array of the next, with copies of value innermost.";
			case WITH_MEMBERS -> "Gives the object the members of an object expression, which replace its class's where"
					+ " they share a name, and returns it.";
			case IS_INSTANCE -> "Whether the value is an object of the class, or of a class that extends it, as"
					+ " Kotlin's is.";
			case KEYS_OF -> "The table's keys as a list, in the order pairs gives them, as Kotlin copies a set or a"
					+ " map's keys into a list.";
			case VALUES_OF -> "The table's values as a list, in the order pairs gives them, as Kotlin copies a map's"
					+ " values into a list.";
			case ADD_ELEMENTS, ADD_KEYS, COPY_ENTRIES, PUT_ENTRIES -> comment;
			// Kotlin's own functions, which only the Kotlin front end uses
			case PAD_START, PAD_END, REMOVE_PREFIX, REMOVE_SUFFIX, SUBSTRING_BEFORE, SUBSTRING_AFTER, REPLACE, LINES,
					COMPARE, REVERSED, CLEAR, INTERSECTION, DIFFERENCE -> comment;
			case FIRST_KEY -> "The first key pairs gives, as a sorted set's first() or a sorted map's firstKey(); nil"
					+ " for an empty table, where Kotlin throws.";
			case LAST_KEY -> "The last key pairs gives, as a sorted set's last() or a sorted map's lastKey(); nil for"
					+ " an empty table, where Kotlin throws.";
			case SORTED_TABLE -> "A table whose pairs walks its keys in sorted order, as a TreeMap and a TreeSet do in"
					+ " Kotlin; comesBefore orders them, and Lua's < when it is nil.";
			case ORDERED_TABLE -> "A table whose pairs walks its keys in the order they were first added, as"
					+ " Kotlin's maps and sets do, but for its hash maps and hash sets. It holds its entries itself;"
					+ " its metatable keeps their order.";
			default -> throw new IllegalStateException("the Kotlin front end does not use " + this);
		};
	}

	/** A call of the helper. */
	public Expression invoke(Expression... arguments) {
		return call(Expression.name(luaName), arguments);
	}

	/** The helper's definition, {@code local function name(...) ... end}. */
	public Statement definition() {
		if (!body.isEmpty()) {
			List<String> names = new ArrayList<>(parameters.isEmpty() ? List.of() : List.of(parameters.split(", ")));
			// A last parameter ... takes the rest of the arguments
			boolean varargs = !names.isEmpty() && names.getLast().equals("...");
			if (varargs) {
				names.removeLast();
			}
			return new Statement.LocalFunction(luaName, new FunctionBody(names, varargs,
					Block.of(new Statement.Verbatim(body.lines().toList()))));
		}
		return new Statement.LocalFunction(luaName, switch (this) {
			case QUOTIENT -> function(List.of("a", "b"),
					local("q", binary(Expression.name("a"), BinaryOperator.FLOOR_DIVIDE, Expression.name("b"))),
					ifThen(
							binary(
									binary(Expression.name("q"), BinaryOperator.LESS, integer(0)),
									BinaryOperator.AND,
									binary(binary(Expression.name("q"), BinaryOperator.MULTIPLY, Expression.name("b")),
											BinaryOperator.NOT_EQUAL, Expression.name("a"))),
							assign("q", binary(Expression.name("q"), BinaryOperator.ADD, integer(1)))),
					returns(Expression.name("q")));
			case TO_INTEGER -> function(List.of("d"),
					ifThen(binary(Expression.name("d"), BinaryOperator.GREATER_EQUAL, integer(0)),
							returns(call(field(Expression.name("math"), "floor"), Expression.name("d")))),
					returns(call(field(Expression.name("math"), "ceil"), Expression.name("d"))));
			case FILLED -> function(List.of("n", "value"),
					local("array", new Expression.Table(List.of())),
					new Statement.NumericFor("i", integer(1), Expression.name("n"), null, Block.of(
							Statement.assignment(
									new Expression.Index(Expression.name("array"), Expression.name("i")),
									Expression.name("value")))),
					returns(Expression.name("array")));
			case LIST_CONTAINS -> function(List.of("list", "value"),
					new Statement.GenericFor(List.of("_", "item"),
							List.of(call(Expression.name("ipairs"), Expression.name("list"))),
							Block.of(ifThen(
									binary(Expression.name("item"), BinaryOperator.EQUAL, Expression.name("value")),
									returns(new Expression.BooleanLiteral(true))))),
					returns(new Expression.BooleanLiteral(false)));
			case LIST_INDEX_OF -> function(List.of("list", "value"),
					new Statement.GenericFor(List.of("i", "item"),
							List.of(call(Expression.name("ipairs"), Expression.name("list"))),
							Block.of(ifThen(
									binary(Expression.name("item"), BinaryOperator.EQUAL, Expression.name("value")),
									returns(binary(Expression.name("i"), BinaryOperator.SUBTRACT, integer(1)))))),
					returns(integer(-1)));
			case STRING_INDEX_OF -> function(List.of("s", "text"),
					local("start", methodCall(Expression.name("s"), "find", Expression.name("text"), integer(1),
							new Expression.BooleanLiteral(true))),
					ifThen(binary(Expression.name("start"), BinaryOperator.EQUAL, new Expression.Nil()),
							returns(integer(-1))),
					returns(binary(Expression.name("start"), BinaryOperator.SUBTRACT, integer(1))));
			case ENDS_WITH -> function(List.of("s", "suffix"),
					returns(binary(
							binary(Expression.name("suffix"), BinaryOperator.EQUAL, string("")),
							BinaryOperator.OR,
							binary(
									methodCall(Expression.name("s"), "sub", new Expression.Unary(UnaryOperator.NEGATE,
											new Expression.Unary(UnaryOperator.LENGTH, Expression.name("suffix")))),
									BinaryOperator.EQUAL,
									Expression.name("suffix")))));
			case TRIM -> function(List.of("s"),
					returns(methodCall(Expression.name("s"), "match", string("^%s*(.-)%s*$"))));
			case TABLE_LENGTH -> function(List.of("t"),
					local("n", integer(0)),
					new Statement.GenericFor(List.of("_"),
							List.of(call(Expression.name("pairs"), Expression.name("t"))),
							Block.of(assign("n", binary(Expression.name("n"), BinaryOperator.ADD, integer(1))))),
					returns(Expression.name("n")));
			default -> throw new IllegalStateException("a helper written as Lua has its body already: " + this);
		});
	}

	private static FunctionBody function(List<String> parameters, Statement... body) {
		return FunctionBody.of(parameters, Block.of(body));
	}

	private static Statement local(String variable, Expression value) {
		return new Statement.Local(List.of(variable), List.of(value));
	}

	private static Statement assign(String variable, Expression value) {
		return Statement.assignment(Expression.name(variable), value);
	}

	private static Statement ifThen(Expression condition, Statement... body) {
		return Statement.ifThen(condition, Block.of(body));
	}

	private static Statement returns(Expression value) {
		return new Statement.Return(List.of(value));
	}
}
