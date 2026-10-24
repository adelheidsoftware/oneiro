package org.adelheid.oneiro.java;

import static org.adelheid.oneiro.lua.Expression.binary;
import static org.adelheid.oneiro.lua.Expression.call;
import static org.adelheid.oneiro.lua.Expression.field;
import static org.adelheid.oneiro.lua.Expression.integer;
import static org.adelheid.oneiro.lua.Expression.methodCall;
import static org.adelheid.oneiro.lua.Expression.string;

import java.util.ArrayList;
import java.util.List;
import org.adelheid.oneiro.lua.BinaryOperator;
import org.adelheid.oneiro.lua.Block;
import org.adelheid.oneiro.lua.Comment;
import org.adelheid.oneiro.lua.Expression;
import org.adelheid.oneiro.lua.FunctionBody;
import org.adelheid.oneiro.lua.Statement;
import org.adelheid.oneiro.lua.UnaryOperator;

/**
 * Small Lua functions for translations that no single expression can say, such as Java's integer division. A module
 * defines only the helpers it uses, near its top, each with a comment naming the Java behaviour it reproduces.
 */
enum Helper {

	QUOTIENT("quotient", "Integer division that rounds toward zero, as Java's / does; Lua's // rounds down."),
	TO_INTEGER("toInteger", "Java's (int) cast of a double: drops the fraction, rounding toward zero."),
	FILLED("filled", "A new array of n copies of value, as Java fills a new array with zeros or false."),
	LIST_CONTAINS("listContains", "Whether the list holds the value, as Java's List.contains."),
	LIST_INDEX_OF("listIndexOf", "The 0-based position of the value in the list, or -1, as Java's List.indexOf."),
	STRING_INDEX_OF("stringIndexOf",
			"The 0-based position of the text in the string, or -1, as Java's String.indexOf."),
	ENDS_WITH("endsWith", "Whether the string ends with the suffix, as Java's String.endsWith."),
	TRIM("trim", "The string without leading and trailing whitespace, as Java's String.trim."),
	COUNT("count", "The number of entries in a map or set, as Java's size(); a Lua table keeps no count."),
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
			            -- A key removed and added again goes to the end, as in Java
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

	String luaName() {
		return luaName;
	}

	/** The comment above the helper, wrapped to stay within 120 columns as a line comment at the top of a file. */
	List<Comment> comment() {
		List<Comment> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : comment.split(" ", -1)) {
			if (!line.isEmpty() && line.length() + 1 + word.length() > WIDTH) {
				lines.add(Comment.line(line.toString()));
				line.setLength(0);
			}
			line.append(line.isEmpty() ? "" : " ").append(word);
		}
		lines.add(Comment.line(line.toString()));
		return lines;
	}

	/** A call of the helper. */
	Expression invoke(Expression... arguments) {
		return call(Expression.name(luaName), arguments);
	}

	/** The helper's definition, {@code local function name(...) ... end}. */
	Statement definition() {
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
			case COUNT -> function(List.of("t"),
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
