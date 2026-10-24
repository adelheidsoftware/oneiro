# Supported Kotlin

What Oneiro accepts in a plugin's Kotlin source and the Lua it produces. The Kotlin front end accepts what these
tables list; anything else fails with a message, either that it is not supported yet or, marked *Never* here, why Lua
cannot follow it and what to write instead. How a plugin written in Kotlin is built is in
[plugin-model.md](plugin-model.md#a-plugin-written-in-kotlin).

The same two rules decide every row as for Java ([java-subset.md](java-subset.md)): the ordinary Kotlin form is
accepted wherever a faithful, readable Lua translation exists, and only genuine gaps between Kotlin and Lua are
rejected, with an error that says why and what to write instead. Each row has a test: most a Kotlin twin of a Java
conformance case, which must give the same Lua, and the rest cases of Kotlin's own, `kotlin-chains` and
`kotlin-idioms`.

## Files and modules

| Kotlin | Lua | Notes |
| --- | --- | --- |
| A file that declares one `object Row` | `src/core/row.lua`: `local Row = {}`, its functions, and `return Row` | The packages below the plugin's root package become folders under `src/`, and the declaration's name, in snake case, the file's name. |
| A file that declares one `class Tally` | `local Tally = {}` and `Tally.__index = Tally`, `Tally.new()`, its methods, and `return Tally` | As a Java class with instances. |
| `enum class Mode { COUNT, LIST }` | `local Mode = { COUNT = "COUNT", LIST = "LIST" }` | A simple enum: each constant is its own name. A comment after a constant goes above it. |
| `enum class Herb(val seedId: Int) { GUAM(5291), ... }` | `local Herb = {}`, `Herb.new(name, ordinal, ...)`, and `Herb.GUAM = Herb.new("GUAM", 0, 5291)` | An object enum: its constants are objects, with `name` and `ordinal`, when the enum has a constructor, properties, functions or constant bodies, or when code reads their order, through `ordinal`, `compareTo`, `<`, a sort in natural order, or a key a sort or comparator orders by. A constant's body sets its own members, `Herb.RANARR.note = ...`. `Herb.__lt` orders the constants as they are declared. |
| A class, object or enum inside another | `local Tab = {}`, with `Bank.Tab = Tab` unless it is private | Its table and members come before those of the class around it. |
| The declaration's KDoc | The header comment, after a first line with the file's path | Comments above the declaration come first. Without KDoc there is a warning, because every Lua file needs a header. |
| Using a class of another file, `Tally()` | `local Tally = require("src/tally")` at the top, and `Tally.new()` | |
| A function's KDoc | Its description as comment lines, then `---@param row integer[]? text` and `---@return boolean complete text` | As in Javadoc, a return text that starts with a name and a colon, `@return complete: text`, names the return value. |
| Code that runs while the module loads, `val SQUARES = build(4)` and an object's `init` block | After the functions it calls | A local used above its definition is declared ahead. A `const val` uses nothing and goes first, where Lua authors put constants. |
| A file that declares two classes, objects or interfaces | *Never*: "Lua has one module per file; move Second into a file of its own." | |
| A name that is not ASCII letters, digits and underscores, `fun größe()` or `` fun `my name`() `` | *Never*: "Lua names are ASCII letters, digits and underscores only; rename größe." | The rest of the file is not checked until the name is renamed. |

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
| An interface with default functions, `interface Describable { fun describe(): String = ... }` | `local Describable = {}` with the default functions, which take `self` | A class that implements it gets each default it does not override: `Herbal.describe = Describable.describe`. The interface's companion object's members are its table's. |
| `class Herbal : Comparable<Herbal>` | `function Herbal.__lt(a, b) return a:compareTo(b) < 0 end` | So that `<` and `table.sort` order the objects as Kotlin's natural order does. |
| A class that implements a function type, `(Herb) -> Unit`, a Java functional interface such as `Runnable`, or a fun interface of the plugin's | `function Planter:__call(...) return self:invoke(...) end` | Its objects are called as the functions the library, or the fun interface's other values, are. |
| A data class with members, `data class Tile(val x: Int, ...) { fun stepsTo(...) }` | A class whose fields are its properties, `Tile.new(x, y, plane)` | The `copy()`, `componentN()`, `equals`, `hashCode` and `toString` Kotlin generates are *Never*: its Lua table has no such functions. |
| A data class of properties alone, `data class Summary(val seen: Int, val level: Int)` | A plain Lua table, built where it is constructed: `{ seen = seen, level = level }` | The class itself produces no Lua, so it also describes tables that Lua code passes in. |
| `==` on a data class or a class that declares `equals` | *Never*: "Kotlin's == compares these objects by equals, but Lua's compares tables by identity; compare their properties instead, or use === for identity." | `===` is Lua's `==`. |
| `fun interface Check { fun test(value: Int): Boolean }` | Nothing; its values are Lua functions | `check.test(value)` is `check(value)`. A class that implements it is called as the function too, through `__call`. Its default functions have no Lua function to be called on, so `check.describe()` is *Never*: "A value of Check is a Lua function, which carries no methods; make describe() a function outside the interface that takes the Check." |
| `Check { it > 0 }`, `Runnable { counts[0]++ }`, `Predicate<Int> { it < 10 }` | The lambda's function | A functional interface's value made of a lambda is the function itself; `predicate.test(value)` is `predicate(value)`. |
| `object : Shape("square") { override fun area() = 1.0 }` | A helper `withMembers(Shape.new("square"), { area = function(self) ... end })` | An object expression is a table of its properties and functions, each function taking `self`; with a superclass of the plugin's, those members go onto a new object of it. One that implements a function type, a fun interface or a Java functional interface is a Lua function. |
| `class Count(val word: String)` inside a function | `local Count = {}` and `Count.__index = Count`, then its members, inside the function | A local class: its functions are the class's, and it may not have private functions or classes of its own. |
| `fun total(vararg numbers: Int)` | `function Grids.total(...)`, which starts `local numbers = { ... }` | Only the last parameter can take any number of values. `total(*more)` passes `table.unpack(more)`. |
| `@LuaName("Ingest") fun ingest(...)` | `function Tracker:Ingest(...)`; every call is `tracker:Ingest(...)` | Keeps a name that existing Lua code calls. |
| `@LuaModule("src/core/levels") object Levels` | Nothing; using it is `local Levels = require("src/core/levels")` | Declares a hand-written module: its functions are `external fun`, and its properties have external accessors, `val PERFECT: Int external get`. |
| Two functions of one name in a class, object or file, `fun sum(a: Int, b: Int)` and `fun sum(a: Int, b: Int, c: Int)` | *Never*: "Lua functions have no parameter types, so two functions cannot share a name; rename one (for example sumAll, sumOne), or give one function default values for the parameters the others leave out." | |
| A property and a function of one name, `private var count` and `fun count()` | *Never*: "The property count and the function count() would share one slot in the Lua table; rename one." | A private function is a local, and shares no slot. |
| Two private members, or nested classes, of one name in the classes of a file | *Never*: "In Lua, the private members and nested classes of the classes in a file are locals of one scope, so two of them cannot both be named helper; rename one." | |
| `class Names : ArrayList<String>()` | *Never*: "Lua has none of ArrayList's code to inherit; keep one in a property instead of extending it." | |
| `class Basket : Iterable<String>` | *Never*: "Lua's for loop walks tables, not iterator objects; give the class a function that returns its list, and loop over that." | |
| `external fun outside(): Int` outside a `@LuaModule` declaration | *Never*: "Only a @LuaModule declaration has external functions; they stand for Lua functions that exist already." | |

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
| `private var settings: HerbConfig? = null`, or `settings = null` | *Never*: "A property of settings needs no value: every call through it calls the settings module, so remove the initializer." | |
| `init { ... }` or a secondary constructor in the plugin class | *Never*: "The client creates the plugin class, and main.lua runs once; put start-up code in startUp() instead of an init block." | |
| `startUp()` or `shutDown()` called by the plugin's own code | *Never*: "startUp() runs once, when the plugin starts; move what it shares into a function of its own and call that." | |
| `HerbPlugin()` anywhere, `remember(this)` in the plugin class | *Never*: the client creates the plugin class once, and `main.lua` has no object to pass around | Use its members directly. |
| `@Subscribe` on a function of another class | *Never*: "Only the plugin class's functions are subscribed with @Subscribe; subscribe this one with Event.<Name>.Subscribe(name, callback), and unsubscribe it when it is done." | |
| `@ConfigGroup` on a class or an object | *Never*: "@ConfigGroup marks an interface of settings; make NotAnInterface an interface whose functions carry @ConfigItem." | |

## Statements

| Kotlin | Lua | Notes |
| --- | --- | --- |
| `val id = row[slot]` | `local id = row[slot]` | A local named like a Lua keyword or a global that the module uses gets a trailing underscore: `end_`, `table_`. So does one named like a local of the file that the same function uses, such as a private function, a property of an object or a member of the plugin class, which it would hide: `local step_ = n - 1` in a private function `step`. |
| `val ground = World.GetGroundHeight(0, x, z)`, of a type annotated `@LuaReturnValues` | `local height, available = World.GetGroundHeight(0, x, z)` | The several values a Lua function returns: each component the code reads, `ground.height()`, is a local of its own, named after the component, or after the local and the component, `eastHeight`, where the function has that name already. `val (low, high) = span(values)` destructures them, and a function of the type returns them, `return Span(low, high)` as `return low, high`. The local exists only as its components: passing it on is *Never*, and so is a parameter of the type. |
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
| `break`, `continue` | `break`, and `goto continue` to a label at the end of the loop's body | The label is `continue2` and up inside a loop that has one already. A label that names the innermost loop, `values@ for` with `break@values` inside it, is the plain jump. Lua's break and continue reach only the innermost loop, so `break@outer` from a loop inside it is *Never*. |
| `require(level in 1..120) { "level $level" }`, `check(value != 99)` | `if not (level >= 1 and level <= 120) then error(formatStr("level {}", level)) end`, `if value == 99 then error("Check failed.") end` | Without a message, the error's is Kotlin's: `Failed requirement.` or `Check failed.`. |
| `assert(ready) { "not ready" }` | *Never*: "Kotlin's assert runs only where the JVM enables assertions, but Lua's assert always runs; write check(condition) { "..." } instead." | |
| `synchronized(lock) { ... }` | *Never*: "Plugins run on one thread, and Lua has no threads to synchronise; run the block's code without synchronized." | |
| `var c: Int` | `local c` | |
| `run { ... }` | `do ... end`, whose locals end with it | |
| `words.forEach { word -> ... }`, `map.forEach { (key, value) -> ... }` | The loop it makes, with the lambda's code as its body | A `return@forEach` would leave the function around the loop, so it is *Never*; write a for loop and use `continue`. So is a `break` or `continue` of a loop around the lambda. `forEach(f)` with a function calls it in the loop, and a reference's small function is opened into the call it makes. |
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
| `value::class.simpleName`, `value.javaClass` | *Never*: "Lua has no runtime type information, so a value has no class to read; test it with is instead." | |
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
| `'é' - 'a'`, `'é'.code` | *Never*: "A Lua string holds bytes, and 'é' takes several, so Lua has no one number for it; compare it as a char, or use its number, 233, as an Int." | |
| `PI` | `math.pi` | |
| `{ value -> value % 2 == 0 }` | `function(value) return math.fmod(value, 2) == 0 end` | `it` is a parameter like any other, and a lambda that never reads its `it` takes none, `function() ... end`. The last expression is the lambda's value; a `return` inside a lambda names it, `return@forEach`. |
| `::isEven`, `::tick`, `this::remember`, `Tile::describe`, `::Tile` | `Functions.isEven`, `function(event) self:tick(event) end`, `function(event) remember(self, event) end`, `function(tile, ...) return tile:describe(...) end`, `Tile.new` | A reference bound to another object, `ticker::tick`, needs a name, as Kotlin reads the object once. A data class of properties alone, `::Seed`, is `function(name) return { name = name } end`, and `Seed::name` reads the field. Where a chain or `forEach` calls the function, its small function is opened into the call, `names.map(::label)` calling `label(self, item)`. |
| `String::length`, `String::uppercase`, `String::toInt`, `::println`, `list::add`, `set::contains`, `map::get`, `::maxOf` | `function(text) return #text end`, `function(text) return text:upper() end`, `function(text) return tonumber(text, 10) end`, `print`, `function(value) list[#list + 1] = value end`, `function(value) return set[value] ~= nil end`, `function(key) return map[key] end`, `math.max` | A reference to the library has a Lua form where Oneiro knows one; for the rest, a lambda says the same. |
| `keep(item)` on a value of a function type | `keep(item)` | |
| `Mode.entries`, `Mode.valueOf(name)`, `mode.name` | `{ Mode.FAST, Mode.SAFE, Mode.OFF }`, `Mode[name]`, `mode` | `valueOf` gives nil for an unknown name, where Kotlin throws. A simple enum's constants have no `ordinal`. |
| `log("Herb tracker started")`, `Inventory.Find(config.Inv.RS3_INV).GetTotal(config.Obj.RS3_COINS)` | `log("Herb tracker started")`, `Inventory.Find(config.Inv.INV):GetTotal(config.Obj.COINS)` | The RS3 API as the bindings describe it, as for Java: constants lose their `RS3_` prefix. |
| `event.inv == config.Inv.RS3_INV` | `event.inv == config.Inv.INV` | The client defines `==` for its values. |
| `ui.StyleSheets.get(id)`, `ui.Hook.ONCLICK.stoppable()`, `position.plus(offset)` | `ui.StyleSheets[id]`, `ui.Hook.ONCLICK`, `position + offset` | As for Java: `@LuaIndex` indexes the table, `@LuaSame` is the receiver itself, and `@LuaOperator` is Lua's operator, which `position + offset` in Kotlin is too. |
| `label.Subscribe(ui.Hook.ONCLICK) { _, x, y -> ... }`, `label.Subscribe(ui.Hook.ONCLICK.stoppable()) { _, x, _ -> x > 10 }` | `label:Subscribe(ui.Hook.ONCLICK, function(_, x, y) ... end)` | The bindings generated for a plugin written in Kotlin have a `Subscribe` for each callback a hook takes, so the callback is a trailing lambda, whose parameters Kotlin types by the hook. As for Java, it receives the component first, and `stoppable()` types a callback that returns whether the listeners after it run. |
| `Event.ServerLogic.Subscribe("tick") { log("tick") }` | `Event.ServerLogic.Subscribe("tick", function() log("tick") end)` | An event that passes nothing calls its callbacks without a value, and reading the payload is *Never*. |
| `"state $state"`, `state.name` of a client enum | `GameState.FromID(state)` | A client enum's value is the client's number, whose name its table's `FromID` gives; a table without `FromID` gives none, and `ordinal` is *Never*. |
| `npc.coordGrid.x = 5`, of a field the bindings mark `@LuaCopy` | *Never*: "npc.coordGrid hands out a copy of the client's value, so changing coordGrid.x here changes nothing in the client; change a local copy instead: val coordGrid = npc.coordGrid; coordGrid.x = ..." | |
| `System.currentTimeMillis()`, `Instant.now()`, `measureTimeMillis { }` | *Never*: "The client gives plugins no clock to read; to measure time, count the ticks of Event.ServerLogic, about every 0.6 seconds, or of Event.Logic, 50 a second." | |
| `Thread(work).start()`, `thread { }`, `Timer()`, `Executors...` | *Never*: "Plugins run on the client's one thread and cannot start threads or schedule work of their own; ..." | |
| A member that needs a permission the build does not declare, or a development-only member outside the dev source set | *Never*: the same errors as for Java | |
| A member that moderation reviews closely | The call, with the same warning as for Java | `@Suppress("oneiro:<area>")` on a function, class or file silences it once the plugin meets what it names. |

## Collections

A list or an array is a Lua sequence that counts from 1, a set a table of its elements as keys, and a map a table of
keys to values, as for Java.

| Kotlin | Lua | Notes |
| --- | --- | --- |
| `ArrayList<Tab>()`, `HashMap<String, Int>()`, `HashSet<Int>()`, `hashMapOf()`, `emptyList()` | `{}` | |
| `mutableMapOf()`, `mutableSetOf()`, `LinkedHashMap()` | A helper `orderedTable()` | Kotlin keeps these in the order their keys were added, and so does the helper's `pairs`. Use `hashMapOf()` when the order does not matter. |
| `mapOf("guam" to 3, "marrentill" to 5)`, `setOf("falador", "catherby")`, and their mutable kin | Helpers `putEntries(orderedTable(), { "guam", 3, "marrentill", 5 })`, `addElements(orderedTable(), { "falador", "catherby" })` | Kept in the order written. `hashMapOf("guam" to 10)` and `hashSetOf("x")` are plain tables, `{ guam = 10 }` and `{ x = true }`. |
| `TreeMap<String, Int>()`, `TreeSet(reverseOrder())`, `sortedMapOf()` | A helper `sortedTable()`, or `sortedTable(function(a, b) return b < a end)` | Its `pairs` walks the keys in sorted order. `set.first()` and `map.firstKey()` are helpers `firstKey(t)`, and `last()` and `lastKey()` `lastKey(t)`. |
| `ArrayList(other)`, `list.toMutableList()`, `HashSet(list)`, `TreeMap(map)` | `table.move(other, 1, #other, 1, {})`; from a set or a map's keys or values, helpers `keysOf` and `valuesOf`; into a set or map, helpers `addElements`, `addKeys` and `copyEntries` | |
| `list.addAll(other)`, `set.addAll(list)`, `map.putAll(other)` | `table.move(other, 1, #other, #list + 1, list)`, or a loop over what the other holds | |
| `listOf(1, 2, 3)`, `mutableListOf(a)`, `arrayOf(...)`, `intArrayOf(...)` | `{ 1, 2, 3 }` | Lua tables cannot hold nil, so a collection cannot store `null`. |
| `IntArray(count)`, `BooleanArray(n)` | A helper `filled(count, 0)`, `filled(n, false)` | |
| `Array(rows) { BooleanArray(columns) }` | A helper `grid(false, rows, columns)` | Each level's initializer makes the same array whatever its index. |
| `list.size`, `map.size` | `#list`, a helper `count(map)` | |
| `list.add(x)`, `list.add(i, x)`, `list[i] = x` | `list[#list + 1] = x`, `table.insert(list, i + 1, x)`, `list[i + 1] = x` | As statements, on lines of their own. |
| `list.removeAt(i)`, `removeFirst()`, `removeLast()` | `table.remove(list, i + 1)`, `table.remove(list, 1)`, `table.remove(list)` | |
| `list.first()`, `last()`, `firstOrNull()`, `getOrNull(i)` | `list[1]`, `list[#list]`, `list[1]`, `list[i + 1]` | An empty list's are nil. |
| `x in list`, `list.indexOf(x)` | Helpers `listContains(list, x)` and `listIndexOf(list, x)` | |
| `list.isEmpty()`, `map.isEmpty()` | `#list == 0`, `next(map) == nil` | `count()`, `any()` and `none()` without a predicate are `#list`, `#list > 0` and `#list == 0`, or for a set or map the helper `count` and `next`. |
| `list.sort()`, `list.sortDescending()`, `list.sortBy { it.herb }`, `list.sortWith(comparator)` | `table.sort(list)`, `table.sort(list, function(a, b) return b < a end)`, `table.sort(list, function(a, b) return a.herb < b.herb end)`, `table.sort(list, ...)` with the comparator's test | `sortByDescending` turns the test around. Lua's sort is not stable, where Kotlin's is: give the comparator a tie-breaker where the order of equal elements matters. |
| `parts.joinToString(", ")` | `table.concat(parts, ", ")` | For strings, chars and numbers. |
| `jobs.addLast(x)`, `jobs.addFirst(x)`, `jobs.removeFirst()` on an `ArrayDeque` | `jobs[#jobs + 1] = x`, `table.insert(jobs, 1, x)`, `table.remove(jobs, 1)` | |
| `String(chars)`, `chars.concatToString()` | `table.concat(chars)` | |
| `map[key] = value`, `map.remove(key)`, `set.add(x)`, `set.remove(x)` | `map[key] = value`, `map[key] = nil`, `set[x] = true`, `set[x] = nil` | |
| `map[key]`, `map.getValue(key)`, `map.getOrDefault(key, 0)`, `key in map`, `map.getOrPut(key) { ... }` | `map[key]`, `map[key]`, `(map[key] or 0)`, `map[key] ~= nil`, a helper `computeIfAbsent` | A missing key is nil, where `getValue` throws. |
| A key or element of a data class, a class that declares `equals`, or a client object | *Never*: Lua compares table keys by identity | Key the map by a string or number. |

## Collection functions

A chain of Kotlin's collection functions on a list, a set, an array, a range or a map's `keys` or `values` becomes one
Lua loop, as the Java front end makes one of a stream: `filter`, `filterNot`, `filterNotNull`, `map` and `mapNotNull`
steps, then the call that gives the result, built up in a local. Each lambda's parameter stands for the loop's
current element, so the lambdas' code reads as written; the first one names the loop's variable. Kotlin runs each step
over the whole collection before the next, and the loop runs every step on one element before the next element, which
differs only for lambdas whose side effects depend on that order. A loop is statements, so a chain is the whole value
of a local, an assignment or a return, or a `forEach` statement of its own; elsewhere it is *Never*: "In Lua a chain of
collection functions is a loop, which runs as statements; make the chain the whole value of a local, an assignment or
a return, or a forEach statement of its own."

| Kotlin | Lua | Notes |
| --- | --- | --- |
| `val names = npcs.filter { it.level > 10 }.map { it.name }` | `local names = {}`, then `for _, it in ipairs(npcs) do if it.level > 10 then names[#names + 1] = it.name end end` | A chain that ends in a step gives a list. `filter { a }.filter { b }` is `if a and b`, and a mapped value that a later step reads goes into a local first. `mapNotNull` leaves out nil values. |
| `(1..limit).map { it * it }`, `(start downTo 1)`, `stock.keys.filter { ... }`, `stock.values.sum()` | `for it = 1, limit do`, `for it = start, 1, -1 do`, `for it in pairs(stock) do`, `for _, value in pairs(stock) do` | A chain over a map's entries is not supported yet. |
| `.toList()`, `.toSet()`, `.toHashSet()` | A list; a set in a helper `orderedTable()`, which keeps the order the elements came in, as Kotlin's does; a plain table | |
| `.sorted()`, `.sortedDescending()`, `.sortedBy { ... }`, `.sortedWith(comparator)` | The list, then `table.sort` with the order's test | With no steps before, a copy of the list, `table.move(list, 1, #list, 1, {})`. Sorting before the end of the chain is *Never*: the loop sees one element at a time. |
| `.forEach { ... }` after steps | The lambda's code inside the loop | |
| `.count { ... }`, `.sum()`, `.sumOf { ... }` | `local count = 0` and `count = count + 1`; `local sum = 0` and `sum = sum + ...` | A sum of decimals starts from `0.0`. |
| `.max()`, `.minOf { ... }`, `.maxByOrNull { ... }`, `.minWith(comparator)` and their kin | `local max`, then `if max == nil or max < value then max = value end` | Nothing gives nil, where Kotlin's forms without `OrNull` throw. `maxBy` compares each element's key with the best's. |
| `.first { ... }`, `.firstOrNull()`, `.find { ... }` | Returned from inside the loop when the chain is returned, otherwise kept in a local and the loop left with `break` | Nothing found is nil, where `first` throws. |
| `.last { ... }`, `.lastOrNull()`, `.findLast { ... }` | Each element that passes, kept until the end | |
| `.any { ... }`, `.all { ... }`, `.none { ... }` | A test that ends the loop once it decides, returning from inside the loop when the chain is returned | |
| `.joinToString(", ", "[", "]") { it.item }` | The parts in a list, then `"[" .. table.concat(parts, ", ") .. "]"` | Without a transform the elements are strings or numbers, which `table.concat` writes as Kotlin does. |
| `?: default` after a chain that finds one value, `?: return` | `found or default`, or `if found == nil then return end` after the loop | |
| `distinct()`, `take()`, `groupBy()` and the other collection functions | Not supported yet | |

## Comparators

A comparator becomes the test of whether one value comes before another, which `table.sort` takes and a loop that
finds a minimum compares with, as in the Java front end.

| Kotlin | Lua | Notes |
| --- | --- | --- |
| `compareBy { it.level }`, `compareByDescending { it.level }` | `a.level < b.level`, `b.level < a.level` | `compareBy({ it.herb }, { it.count })` compares by each key in turn. |
| `compareBy<Drop> { it.value }.thenBy { it.item }`, `thenByDescending` | `a.value < b.value or (a.value == b.value and a.item < b.item)` | |
| `naturalOrder()`, `reverseOrder()`, `comparator.reversed()` | `a < b`, `b < a`, the comparator's test turned around | |
| `{ a, b -> b.length - a.length }`, an object expression with `compare` | `#b - #a < 0` | `x.compareTo(y)` and `compareValues(x, y)` of numbers, strings, chars and an enum's constants are `x < y`; any other comparator is called on the two. |

## Strings

Lua has no regular expressions. It has patterns of its own, which `org.adelheid.oneiro.api.LuaString` offers, as for
Java ([java-subset.md](java-subset.md#lua-patterns)): each of its functions is Lua's string function of the same name.

| Kotlin | Lua | Notes |
| --- | --- | --- |
| `text.length`, `text.isEmpty()` | `#text`, `text == ""` | Lua counts bytes, which is the length for ASCII text. |
| `text.substring(1, 3)` | `text:sub(2, 3)` | |
| `name.startsWith(prefix)`, `name.endsWith(".png")` | `name:sub(1, #prefix) == prefix`, `name:sub(-4) == ".png"` | Another suffix goes through a helper. |
| `"x" in text`, `text.indexOf("x")` | `text:find("x", 1, true) ~= nil`, a helper `stringIndexOf(text, "x")` | The text is found as it is written, not as a pattern. |
| `text.uppercase()`, `text.lowercase()`, `text.trim()`, `text.repeat(3)`, `text.reversed()` | `text:upper()`, `text:lower()`, a helper `trim(text)`, `text:rep(3)`, `text:reverse()` | |
| `a.equals(b, ignoreCase = true)` | `a:lower() == b:lower()` | |
| `text[i]`, `text.first()`, `text.last()` | `text:sub(i + 1, i + 1)`, `text:sub(1, 1)`, `text:sub(-1)` | |
| `line.split(",")` | A helper `split(line, ",", -1)` | The parts between the separators, empty ones among them, as Kotlin splits; the separator stands for itself. A `Regex` is *Never*: "Lua has no regular expressions, so split takes only a string separator that stands for itself, such as a comma; for a pattern, use LuaString.gmatch." |
| `Regex(...)`, `text.toRegex()`, `text.matches(regex)`, `java.util.regex` | *Never*: "Lua has no regular expressions; use LuaString.match, find, gsub or gmatch with a Lua pattern." | |
| `LuaString.match(line, "^You catch an? (.+)%.$")` | `line:match("^You catch an? (.+)%.$")` | The first capture, or the whole match when the pattern has none; null when nothing matches. |
| `LuaString.captures(line, "^Drop: (%d+) x (.+)$")` | `{ line:match("^Drop: (%d+) x (.+)$") }` | Every capture of the first match, in a list; empty when nothing matches. |
| `LuaString.find(line, "%d")` | `(line:find("%d") or 0) - 1` | Where the first match starts, counted from 0; -1 when nothing matches. |
| `LuaString.gsub(line, "%d+", "#")` | `(line:gsub("%d+", "#"))` | `%1` to `%9` in the replacement stand for the captures. The parentheses keep only the text, not the count Lua's `gsub` also gives. |
| `for (word in LuaString.gmatch(line, "%a+"))` | `for word in line:gmatch("%a+") do` | Each match's first capture, or the whole match; anywhere but a for loop it is *Never*, as Lua gives the matches through an iterator. |
