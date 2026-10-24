# Supported Kotlin

What Oneiro accepts in a plugin's Kotlin source and the Lua it produces. The Kotlin front end is in progress: it
accepts what these tables list, and anything else fails with a message that it is not supported yet. How a plugin
written in Kotlin is built is in [plugin-model.md](plugin-model.md#a-plugin-written-in-kotlin).

The same two rules decide every row as for Java ([java-subset.md](java-subset.md)): the ordinary Kotlin form is
accepted wherever a faithful, readable Lua translation exists, and only genuine gaps between Kotlin and Lua are
rejected, with an error that says why and what to write instead. Each row has a test: most a Kotlin twin of a Java
conformance case, which must give the same Lua.

## Files and modules

| Kotlin | Lua | Notes |
| --- | --- | --- |
| A file that declares one `object Row` | `src/core/row.lua`: `local Row = {}`, its functions, and `return Row` | The packages below the plugin's root package become folders under `src/`, and the declaration's name, in snake case, the file's name. |
| A file that declares one `class Tally` | `local Tally = {}` and `Tally.__index = Tally`, `Tally.new()`, its methods, and `return Tally` | As a Java class with instances. |
| `enum class Mode { COUNT, LIST }` | `local Mode = { COUNT = "COUNT", LIST = "LIST" }` | A simple enum: each constant is its own name. A comment after a constant goes above it. |
| A class, object or enum inside another | `local Tab = {}`, with `Bank.Tab = Tab` unless it is private | Its table and members come before those of the class around it. |
| The declaration's KDoc | The header comment, after a first line with the file's path | Comments above the declaration come first. Without KDoc there is a warning, because every Lua file needs a header. |
| Using a class of another file, `Tally()` | `local Tally = require("src/tally")` at the top, and `Tally.new()` | |
| A function's KDoc | Its description as comment lines, then `---@param row integer[]? text` and `---@return boolean complete text` | As in Javadoc, a return text that starts with a name and a colon, `@return complete: text`, names the return value. |
| Code that runs while the module loads, `val SQUARES = build(4)` and an object's `init` block | After the functions it calls | A local used above its definition is declared ahead. A `const val` uses nothing and goes first, where Lua authors put constants. |

## Classes and objects

| Kotlin | Lua | Notes |
| --- | --- | --- |
| `fun complete(row: List<Int?>?): Boolean` in an object | `function Row.complete(row)` | A private function is a local, `local function complete(row)`. |
| `class Tally(var label: String)` | `function Tally.new(label)`, which sets `self.label = label` | |
| `private var total = 0` in a class | `self.total = 0` in `Tally.new()` | Each property gets its value in declaration order, with the `init` blocks among them. In an `init` block, a constructor property's name is its parameter, as in Kotlin. |
| `fun add(amount: Int)` in a class | `function Tally:add(amount)` | Its properties are `self.total`, and a call of another method is `self:sum()`. A private method is a local that takes the object first, `bump(self)`. |
| `fun sum(): Int = total` | `function Tally:sum() return self.total end` | An expression body is the function's `return`. |
| `companion object { const val START = 0 }` | `Tally.START = 0` | A companion object's members are the class table's own. |
| `inner class Tab(...)` | `Tab.new(outer, ...)`, which keeps `self.outer` | The outer object's members are `self.outer.owner`, and `this@Bank` is `self.outer`. |
| `open class Counters` and `class Boosted : Counters()` | `local Boosted = setmetatable({}, { __index = Counters })`, and `setmetatable(Counters.new(), Boosted)` in `Boosted.new()` | `super.level()` is `Counters.level(self)`. An abstract function has no Lua; its subclasses give the code. |
| `fun add(amount: Int = STEP, negative: Boolean = false)` | `function Overloads:add(amount, negative)`, which starts `amount = amount or STEP`, and for a `Boolean` `if negative == nil then negative = false end` | A call that leaves out an argument before one it gives passes nil. Lua cannot tell a null argument from a left-out one, so a parameter that may be null takes no default but null. The constructor's parameters take defaults too, and the class's KDoc `@param` and `@property` tags give their annotations. |
| An interface of abstract functions alone, `interface Measured { fun area(): Double }` | Nothing; it is a type | Its values are objects of the classes that implement it, `shape:area()`. |
| A data class with members, `data class Tile(val x: Int, ...) { fun stepsTo(...) }` | A class whose fields are its properties, `Tile.new(x, y, plane)` | The `copy()`, `componentN()`, `equals`, `hashCode` and `toString` Kotlin generates are *Never*: its Lua table has no such functions. |
| A data class of properties alone, `data class Summary(val seen: Int, val level: Int)` | A plain Lua table, built where it is constructed: `{ seen = seen, level = level }` | The class itself produces no Lua, so it also describes tables that Lua code passes in. |
| `==` on a data class or a class that declares `equals` | *Never*: "Kotlin's == compares these objects by equals, but Lua's compares tables by identity; compare their properties instead, or use === for identity." | `===` is Lua's `==`. |
| `fun interface Check { fun test(value: Int): Boolean }` | Nothing; its values are Lua functions | `check.test(value)` is `check(value)`. |
| `fun total(vararg numbers: Int)` | `function Grids.total(...)`, which starts `local numbers = { ... }` | Only the last parameter can take any number of values. `total(*more)` passes `table.unpack(more)`. |
| `@LuaName("Ingest") fun ingest(...)` | `function Tracker:Ingest(...)`; every call is `tracker:Ingest(...)` | Keeps a name that existing Lua code calls. |
| `@LuaModule("src/core/levels") object Levels` | Nothing; using it is `local Levels = require("src/core/levels")` | Declares a hand-written module: its functions are `external fun`, and its properties have external accessors, `val PERFECT: Int external get`. |

## The plugin class and settings

| Kotlin | Lua | Notes |
| --- | --- | --- |
| `class HerbPlugin : Plugin()` | `main.lua`, with no module table | Its properties and functions, whatever their visibility, are locals of `main.lua`. Only one class extends `Plugin`, in the main source set. |
| `class Cheats : DevPlugin()` in the dev source set | `dev/main.lua`, which never ships | Its `PluginShutdown` runs the plugin's own first. |
| `private const val REPORT_EVERY = 100` in the plugin class's file | `local REPORT_EVERY = 100` at the top of `main.lua` | |
| `override fun startUp()` | Its statements, after every member is defined | They run once, when the client loads the file. |
| `@Subscribe fun onServerLogic(tick: ServerLogicEventPayload)` | `local function onServerLogic()`, and `Event.ServerLogic.Subscribe("<id>.onServerLogic", onServerLogic)` after the `startUp()` statements | The parameter's type names the event. An event that passes nothing has an empty payload class, and its function takes no parameter. `EntityDraw`'s function returns a `Boolean`. |
| `override fun shutDown()` | The global `PluginShutdown`, which unsubscribes every `@Subscribe` function and then runs the body | |
| `@ConfigGroup("herbs") interface HerbConfig` | `src/herb_config.lua`: one function per setting, over `PersistentDB` | Each setting is kept under `"<group>.<keyName>"`. |
| `@ConfigItem(keyName = "greet", name = "Greeting") fun greet(): Boolean = true` | `function HerbConfig.greet()`, which returns the stored value, or the default when nothing is stored | A setting is a `Boolean`, an `Int` or `Long`, a `Double`, a `String`, or an enum of the plugin's, kept by its constant's name. |
| `@ConfigItem(...) fun setRuns(runs: Int)` | `function HerbConfig.setRuns(runs)`, which calls the typed setter | A setter has one parameter and no body. |
| `private lateinit var settings: HerbConfig` and `settings.greet()` | `HerbConfig.greet()` | The property holds nothing: every call through it calls the settings module. |

## Statements

| Kotlin | Lua | Notes |
| --- | --- | --- |
| `val id = row[slot]` | `local id = row[slot]` | A local named like a Lua keyword or a global that the module uses gets a trailing underscore: `end_`, `table_`. |
| `val found = firstWith(names, "B") ?: return 0` | `local found = ...` and `if found == nil then return 0 end` | |
| `if (row == null) { return false }` | `if row == nil then return false end` | |
| `when (mode) { Mode.FAST -> ...; Mode.SAFE, Mode.OFF -> ...; else -> ... }` | `if mode == Mode.FAST then ... elseif mode == Mode.SAFE or mode == Mode.OFF then ... else ... end` | A subject with a call, or one Lua computes that more than one condition compares, is read once into a local named after it: `local size = #values`. `in 1..5`, `in list`, `is Boss` and a guard, `is Monster if level > 100`, are conditions too. |
| `return when (...) { ... }`, `val x = if (...) { ... } else { ... }` | Each branch returns, or assigns the local | An `if` with single values that are never false or nil stays an expression: `local sign = n < 0 and "negative" or "positive"`. |
| `for (slot in 0 until 5)` | `for slot = 1, 5 do` when `slot` only indexes lists, in the body; otherwise `for slot = 0, 4 do` | As in Java, a loop counts from 1 when its variable is used only as an index, in `slot + 1`, or compared with another loop variable that counts from 1. |
| `for (name in names)`, over a list or an array | `for _, name in ipairs(names) do` | |
| `for (sprite in set)`, `for ((key, value) in map)` | `for sprite in pairs(set) do`, `for key, value in pairs(map) do` | `map.keys` and `map.values` are loops over the map with one variable. |
| `for (i in from downTo 1)`, `for (i in 0..limit step 2)`, `for (i in values.indices)` | `for i = from, 1, -1 do`, `for i = 0, limit, 2 do`, `for i = 1, #values do` or `for i = 0, #values - 1 do` | |
| `for (c in text)` | `for c in text:gmatch(".") do` | |
| `while (n > 1) { ... }`, `do { ... } while (value > 0)` | `while n > 1 do ... end`, `repeat ... until not (value > 0)` | |
| `break`, `continue` | `break`, and `goto continue` to a label at the end of the loop's body | The label is `continue2` and up inside a loop that has one already. Lua's break and continue reach only the innermost loop, so `break@outer` is *Never*. |
| `var c: Int` | `local c` | |
| `run { ... }` | `do ... end`, whose locals end with it | |
| `words.forEach { word -> ... }`, `map.forEach { (key, value) -> ... }` | The loop it makes, with the lambda's code as its body | A `return@forEach` would leave the function around the loop, so it is *Never*; write a for loop and use `continue`. |
| `words.removeAll { it.length < 3 }` | A loop from the end that removes with `table.remove`; for a set, a loop of `pairs` | |
| `firstSeen.getOrPut(name) { tick }`, `firstSeen.putIfAbsent(name, tick)` | `if firstSeen[name] == nil then firstSeen[name] = tick end` | |
| `println(value)` | `print(...)` of the value as text | |
| `ticks++`, `nextReport += REPORT_EVERY` | `ticks = ticks + 1`, `nextReport = nextReport + REPORT_EVERY` | |
| `throw IllegalArgumentException("plane $plane")` | `error(formatStr("plane {}", plane))` | Only the message survives, or the class's name when there is none. `error("...")` is Lua's `error`. |
| `try { ... } catch (e: RuntimeException) { ... }` | `local ok, e = pcall(function() ... end)` and `if not ok then ... end` | Lua errors carry a value, not a class, so the one catch names `Exception`, `RuntimeException` or `Throwable`. A try block that returns does so on every path, and its value is returned after `pcall`; `break` and `continue` cannot leave it. `e.message` is the error value itself, and `throw e` raises it again as it is, `error(e, 0)`. |
| `try { ... } finally { ... }` | The try block, and a catch block, each under `pcall`, then the finally block, then the error raised again | |
| `return try { measured(text) } catch (e: Exception) { fallback }`, `val x = try ...` | Each block returns its value, or assigns it | |
| `return Reader(text).use { reader -> reader.first() }` | `local reader = Reader.new(text)`, the lambda's code under `pcall`, `reader:close()`, then the error raised again or the value returned | The resource is a class of the plugin's, which declares `close()`. |
| `val failure = IllegalStateException("broken")` | `local failure = "broken"` | An exception kept as a value is its message, the value `error` raises. |
| `firstWith(names, "C")?.let { name -> ... }` | `local name = Targets.firstWith(names, "C")` and `if name ~= nil then ... end` | |
| `target?.refresh()` | `if target ~= nil then target:refresh() end` | The receiver is a name or a property read. |
| A comment on its own line | The same comment above the statement | |

## Expressions

| Kotlin | Lua | Notes |
| --- | --- | --- |
| `null`, `true`, `false`, integer and decimal literals | `nil`, `true`, `false`, the number | Underscores and the `L` and `f` suffixes go; `0b101` is `5`. A `Double` keeps its point, `2.0`, which Lua reads as a float. |
| `"""..."""` raw strings, with `trimIndent()` or `trimMargin()` | The text, between `[[` and `]]` when it has several lines | The trimming is worked out when compiling. |
| `id == null \|\| id < 0`, `!settings.hideHans()` | `id == nil or id < 0`, `not HerbConfig.hideHans()` | |
| `a / b` and `a % b` on integers | `a // b` and `a % b` when both are known never to be negative; otherwise a helper `quotient(a, b)` and `math.fmod(a, b)` | Kotlin rounds integer division toward zero, Lua's `//` down. A value is known never to be negative when it is a non-negative literal, a size, an `abs`, or a counter that only goes up. |
| `x shl 2`, `x shr 4`, `x ushr 28`, `x and 0xF`, `x or y`, `x xor 3`, `x.inv()` | `x << 2`, `x // 16`, `(x & 0xFFFFFFFF) >> 28`, `x & 0xF`, `x \| y`, `x ~ 3`, `~x` | On booleans, `and`, `or` and `xor` are `and`, `or` and `~=`; Kotlin evaluates both sides, so a right side with a call goes into a local first. |
| `value ?: default` | `value or default` | For a `Boolean?`, `setting ?: true` is `setting ~= false`, and another default goes through a helper that replaces only nil. `?: throw` and `?: error(...)` are `or error(...)`. |
| `target?.name` | `target and target.name` | The receiver is a name or a property read. |
| `value!!` | `value` | Lua raises an error where a nil is used. |
| `creature is Monster`, `value is String` | `isInstance(creature, Monster)` through a helper for a class that others extend, `getmetatable(creature) == Boss` for one nothing extends, `type(value) == "string"` | A simple enum's constant is `Mode[x] == x`. A client object, a list or another table has no type Lua can tell, and is *Never*. |
| `x in 1..5`, `x in 0 until n` | `x >= 1 and x <= 5`, `x >= 0 and x < n` | |
| `d.toInt()` of a `Double` | A helper `toInteger(d)` that drops the fraction | Other conversions keep the Lua number. `a.floorDiv(b)` and `a.mod(b)` are `a // b` and `a % b`. |
| `minOf(a, b)`, `maxOf(a, b)`, `abs(x)`, `Math.max(a, b)` | `math.min(a, b)`, `math.max(a, b)`, `math.abs(x)` | |
| `Int.MAX_VALUE`, `Long.MIN_VALUE` | `2147483647`, `math.mininteger` | |
| `row[slot]` on a `List` or an array, `event.slotsUpdated.size` | `row[slot + 1]`, or `row[slot]` in a loop that counts from 1; `#event.slotsUpdated` | |
| `"Coins in the backpack: $coins"`, `"Hello " + name` | `formatStr("Coins in the backpack: {}", coins)`, `formatStr("Hello {}", name)` | A `Long` goes through `tostring`, because the client hands every number to `formatStr` as a double. Strings joined with nothing else are `a .. b`, and `text += "x"` is `text = text .. "x"`. A list, set or map is written as Kotlin writes it, through a helper; an object of the plugin's by its own `toString()`. |
| `"%5d apples at %.2f".format(count, share)`, `String.format(...)` | `formatStr("{:>5} apples at {:.2f}", count, share)` | The format is a literal, translated as for Java's `String.format`. |
| `StringBuilder("names:")`, `text.append(" ").append(name)`, `text.toString()` | `{ "names:" }`, `text[#text + 1] = " "` and `text[#text + 1] = name`, `table.concat(text)` | |
| `buildString { append(flag) }` | `local text = {}`, `text[#text + 1] = tostring(flag)`, then `table.concat(text)` | Its value goes to a return, a local or an assignment. |
| `count.toString()`, `ratio.toString()`, `text.toInt()` | `tostring(count)`, `formatStr("{}", ratio)`, `tonumber(text, 10)` | Parsing gives nil for text that is no number, where Kotlin throws. |
| `'a'`, `c - 'a'`, `'a' + index`, `c.code`, `code.toChar()` | `"a"`, `string.byte(c) - string.byte("a")`, `string.char(string.byte("a") + index)`, `string.byte(c)`, `string.char(code)` | A `Char` is a one-character string, and chars compare as strings. `c.isDigit()` and its kin use Lua's character classes, `c:find("%d") ~= nil`, which know ASCII only. |
| `PI` | `math.pi` | |
| `{ value -> value % 2 == 0 }` | `function(value) return math.fmod(value, 2) == 0 end` | `it` is a parameter like any other. The last expression is the lambda's value; a `return` inside a lambda names it, `return@forEach`. |
| `::isEven`, `::tick`, `this::remember`, `Tile::describe`, `::Tile` | `Functions.isEven`, `function(event) self:tick(event) end`, `function(event) remember(self, event) end`, `function(tile, ...) return tile:describe(...) end`, `Tile.new` | A reference bound to another object, `ticker::tick`, needs a name, as Kotlin reads the object once. |
| `keep(item)` on a value of a function type | `keep(item)` | |
| `Mode.entries`, `Mode.valueOf(name)`, `mode.name` | `{ Mode.FAST, Mode.SAFE, Mode.OFF }`, `Mode[name]`, `mode` | `valueOf` gives nil for an unknown name, where Kotlin throws. A simple enum's constants have no `ordinal`. |
| `log("Herb tracker started")`, `Inventory.Find(config.Inv.RS3_INV).GetTotal(config.Obj.RS3_COINS)` | `log("Herb tracker started")`, `Inventory.Find(config.Inv.INV):GetTotal(config.Obj.COINS)` | The RS3 API as the bindings describe it, as for Java: constants lose their `RS3_` prefix. |
| `event.inv == config.Inv.RS3_INV` | `event.inv == config.Inv.INV` | The client defines `==` for its values. |
| A member that needs a permission the build does not declare, or a development-only member outside the dev source set | *Never*: the same errors as for Java | |
| A member that moderation reviews closely | The call, with the same warning as for Java | `@Suppress("oneiro:<area>")` on a function, class or file silences it once the plugin meets what it names. |

## Collections

A list or an array is a Lua sequence that counts from 1, a set a table of its elements as keys, and a map a table of
keys to values, as for Java.

| Kotlin | Lua | Notes |
| --- | --- | --- |
| `ArrayList<Tab>()`, `HashMap<String, Int>()`, `HashSet<Int>()`, `hashMapOf()`, `emptyList()` | `{}` | |
| `mutableMapOf()`, `mutableSetOf()`, `LinkedHashMap()` | A helper `orderedTable()` | Kotlin keeps these in the order their keys were added, and so does the helper's `pairs`. Use `hashMapOf()` when the order does not matter. |
| `listOf(1, 2, 3)`, `mutableListOf(a)`, `arrayOf(...)`, `intArrayOf(...)` | `{ 1, 2, 3 }` | Lua tables cannot hold nil, so a collection cannot store `null`. |
| `IntArray(count)`, `BooleanArray(n)` | A helper `filled(count, 0)`, `filled(n, false)` | |
| `Array(rows) { BooleanArray(columns) }` | A helper `grid(false, rows, columns)` | Each level's initializer makes the same array whatever its index. |
| `list.size`, `map.size` | `#list`, a helper `count(map)` | |
| `list.add(x)`, `list.add(i, x)`, `list[i] = x` | `list[#list + 1] = x`, `table.insert(list, i + 1, x)`, `list[i + 1] = x` | As statements, on lines of their own. |
| `list.removeAt(i)`, `removeFirst()`, `removeLast()` | `table.remove(list, i + 1)`, `table.remove(list, 1)`, `table.remove(list)` | |
| `list.first()`, `last()`, `firstOrNull()`, `getOrNull(i)` | `list[1]`, `list[#list]`, `list[1]`, `list[i + 1]` | An empty list's are nil. |
| `x in list`, `list.indexOf(x)` | Helpers `listContains(list, x)` and `listIndexOf(list, x)` | |
| `list.isEmpty()`, `map.isEmpty()` | `#list == 0`, `next(map) == nil` | |
| `parts.joinToString(", ")` | `table.concat(parts, ", ")` | For strings, chars and numbers. |
| `jobs.addLast(x)`, `jobs.addFirst(x)`, `jobs.removeFirst()` on an `ArrayDeque` | `jobs[#jobs + 1] = x`, `table.insert(jobs, 1, x)`, `table.remove(jobs, 1)` | |
| `String(chars)`, `chars.concatToString()` | `table.concat(chars)` | |
| `map[key] = value`, `map.remove(key)`, `set.add(x)`, `set.remove(x)` | `map[key] = value`, `map[key] = nil`, `set[x] = true`, `set[x] = nil` | |
| `map[key]`, `map.getOrDefault(key, 0)`, `key in map`, `map.getOrPut(key) { ... }` | `map[key]`, `(map[key] or 0)`, `map[key] ~= nil`, a helper `computeIfAbsent` | |
| A key or element of a data class, a class that declares `equals`, or a client object | *Never*: Lua compares table keys by identity | Key the map by a string or number. |

## Strings

| Kotlin | Lua | Notes |
| --- | --- | --- |
| `text.length`, `text.isEmpty()` | `#text`, `text == ""` | Lua counts bytes, which is the length for ASCII text. |
| `text.substring(1, 3)` | `text:sub(2, 3)` | |
| `name.startsWith(prefix)`, `name.endsWith(".png")` | `name:sub(1, #prefix) == prefix`, `name:sub(-4) == ".png"` | Another suffix goes through a helper. |
| `"x" in text`, `text.indexOf("x")` | `text:find("x", 1, true) ~= nil`, a helper `stringIndexOf(text, "x")` | The text is found as it is written, not as a pattern. |
| `text.uppercase()`, `text.lowercase()`, `text.trim()`, `text.repeat(3)`, `text.reversed()` | `text:upper()`, `text:lower()`, a helper `trim(text)`, `text:rep(3)`, `text:reverse()` | |
| `a.equals(b, ignoreCase = true)` | `a:lower() == b:lower()` | |
| `text[i]`, `text.first()`, `text.last()` | `text:sub(i + 1, i + 1)`, `text:sub(1, 1)`, `text:sub(-1)` | |
| `line.split(",")` | A helper `split(line, ",", -1)` | The parts between the separators, empty ones among them, as Kotlin splits; the separator stands for itself. |
