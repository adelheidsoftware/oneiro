# Supported Kotlin

What Oneiro accepts in a plugin's Kotlin source and the Lua it produces. The Kotlin front end is in progress, and the
Gradle plugin does not compile Kotlin plugins yet; until it does, write plugins in Java ([java-subset.md](java-subset.md)).

The same two rules decide every row as for Java: the ordinary Kotlin form is accepted wherever a faithful, readable
Lua translation exists, and only genuine gaps between Kotlin and Lua are rejected, with an error that says why and what
to write instead. Every row is a conformance case: a Kotlin twin of a Java case, which must give the same Lua. Anything
these tables do not list fails with a message that it is not supported yet.

## Files and modules

| Kotlin | Lua | Notes |
| --- | --- | --- |
| A file that declares one `object Row` | `src/core/row.lua`: `local Row = {}`, its functions, and `return Row` | The packages below the plugin's root package become folders under `src/`, and the object's name, in snake case, the file's name. |
| The object's KDoc | The header comment, after a first line with the file's path | Comments above the declaration come first. Without KDoc there is a warning, because every Lua file needs a header. |
| `fun complete(row: List<Int?>?): Boolean` in the object | `function Row.complete(row)` | |
| A function's KDoc | Its description as comment lines, then `---@param row integer[]? text` and `---@return boolean complete text` | As in Javadoc, a return text that starts with a name and a colon, `@return complete: text`, names the return value. |

## Statements

| Kotlin | Lua | Notes |
| --- | --- | --- |
| `val id = row[slot]` | `local id = row[slot]` | |
| `if (row == null) { return false }` | `if row == nil then return false end` | |
| `for (slot in 0 until 5)` | `for slot = 1, 5 do` when `slot` only indexes lists, in the body; otherwise `for slot = 0, 4 do` | As in Java, a loop counts from 1 when its variable is used only as an index, in `slot + 1`, or compared with another loop variable that counts from 1. |
| A comment on its own line | The same comment above the statement | |

## Expressions

| Kotlin | Lua | Notes |
| --- | --- | --- |
| `null`, `true`, `false`, integer literals | `nil`, `true`, `false`, the number | |
| `id == null \|\| id < 0` | `id == nil or id < 0` | |
| `row[slot]` on a `List` | `row[slot + 1]`, or `row[slot]` in a loop that counts from 1 | |
