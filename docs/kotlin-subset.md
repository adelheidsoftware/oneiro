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
| `enum class Mode { COUNT, LIST }` | `local Mode = { COUNT = "COUNT", LIST = "LIST" }` | A simple enum: each constant is its own name. |
| The declaration's KDoc | The header comment, after a first line with the file's path | Comments above the declaration come first. Without KDoc there is a warning, because every Lua file needs a header. |
| Using a class of another file, `Tally()` | `local Tally = require("src/tally")` at the top, and `Tally.new()` | |
| A function's KDoc | Its description as comment lines, then `---@param row integer[]? text` and `---@return boolean complete text` | As in Javadoc, a return text that starts with a name and a colon, `@return complete: text`, names the return value. |

## Classes and objects

| Kotlin | Lua | Notes |
| --- | --- | --- |
| `fun complete(row: List<Int?>?): Boolean` in an object | `function Row.complete(row)` | |
| `private var total = 0` in a class | `self.total = 0` in `Tally.new()` | Each property gets its value in declaration order, with the `init` blocks among them. |
| `fun add(amount: Int)` in a class | `function Tally:add(amount)` | Its properties are `self.total`, and a call of another method is `self:sum()`. |
| `fun sum(): Int = total` | `function Tally:sum() return self.total end` | An expression body is the function's `return`. |

## The plugin class and settings

| Kotlin | Lua | Notes |
| --- | --- | --- |
| `class HerbPlugin : Plugin()` | `main.lua`, with no module table | Its properties and functions, whatever their visibility, are locals of `main.lua`. |
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
| `val id = row[slot]` | `local id = row[slot]` | |
| `if (row == null) { return false }` | `if row == nil then return false end` | |
| `for (slot in 0 until 5)` | `for slot = 1, 5 do` when `slot` only indexes lists, in the body; otherwise `for slot = 0, 4 do` | As in Java, a loop counts from 1 when its variable is used only as an index, in `slot + 1`, or compared with another loop variable that counts from 1. |
| `ticks++`, `nextReport += REPORT_EVERY` | `ticks = ticks + 1`, `nextReport = nextReport + REPORT_EVERY` | |
| A comment on its own line | The same comment above the statement | |

## Expressions

| Kotlin | Lua | Notes |
| --- | --- | --- |
| `null`, `true`, `false`, integer literals | `nil`, `true`, `false`, the number | |
| `id == null \|\| id < 0`, `!settings.hideHans()` | `id == nil or id < 0`, `not HerbConfig.hideHans()` | |
| `row[slot]` on a `List`, `event.slotsUpdated.size` | `row[slot + 1]`, or `row[slot]` in a loop that counts from 1; `#event.slotsUpdated` | |
| `"Coins in the backpack: $coins"` | `formatStr("Coins in the backpack: {}", coins)` | A `Long` goes through `tostring`, because the client hands every number to `formatStr` as a double. |
| `log("Herb tracker started")`, `Inventory.Find(config.Inv.RS3_INV).GetTotal(config.Obj.RS3_COINS)` | `log("Herb tracker started")`, `Inventory.Find(config.Inv.INV):GetTotal(config.Obj.COINS)` | The RS3 API as the bindings describe it, as for Java: constants lose their `RS3_` prefix. |
| `event.inv == config.Inv.RS3_INV` | `event.inv == config.Inv.INV` | The client defines `==` for its values. |
| A member that needs a permission the build does not declare, or a development-only member outside the dev source set | *Never*: the same errors as for Java | |
| A member that moderation reviews closely | The call, with the same warning as for Java | `@Suppress("oneiro:<area>")` on a function, class or file silences it once the plugin meets what it names. |
