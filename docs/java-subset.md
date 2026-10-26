# Supported Java

What Oneiro accepts in a plugin's Java source and the Lua it produces. Every row is a conformance test case. This page
covers plain Java, the RS3 API, `try`/`catch`, several return values, and the plugin class and its settings. How a
plugin is built, packaged and linked into the client is in [plugin-model.md](plugin-model.md), and the Kotlin that a
plugin can be written in instead is in [kotlin-subset.md](kotlin-subset.md).

Every row not marked *Never* works, and the conformance suite has a case for each. Rows marked *Never* are genuine
gaps between Java and Lua: they fail with the message shown, a compile-time error that fails the build. A library
method or class these tables do not list fails the same way, with a message that it is "not supported yet"; the Java
library is large, and the tables list what plugins use.

Two rules decide every row:

- **Idiomatic source first.** If a faithful, readable Lua translation exists, the ordinary Java form is accepted.
- **Only genuine gaps are rejected**, with an error that says why and what to write instead. Where a gap needs a
  replacement, Oneiro offers a small, clearly documented API (such as `LuaString` for Lua patterns).

Some translations need a few lines of Lua that no single expression can say, such as Java's integer division. Those
become **helpers**: small `local function`s with a comment, emitted near the top of the module that uses them, and
only when it uses them. Each one is listed in [Helpers](#helpers).

## Files and modules

| Java | Lua | Notes |
| --- | --- | --- |
| One top-level type per file, `<root>.core.OpeningTable` | `src/core/opening_table.lua` | `<root>` is the plugin's root package. A second top-level type in the same file is rejected. |
| Class name `OpeningTable`, `NPCTracker` | Module table `OpeningTable`, file `opening_table.lua`, `npc_tracker.lua` | File names are snake case; an acronym stays one word. |
| Class Javadoc | Header comment at the top of the file, after a first line with the file's path: `-- src/core/opening_table.lua` | The client's errors name a file only by its first line, so the path there makes them say which file they come from. A class without Javadoc gets a warning, because every Lua file needs a header. |
| `import <root>.core.Row;` (used) | `local Row = require("src/core/row")` | In import order, after the header. Unused imports produce nothing. |
| `import static <root>.core.Row.complete;` | `local Row = require("src/core/row")`, calls become `Row.complete(...)` | |
| Name that is a Lua keyword (`end`, `local`, `nil`, `repeat`, ...), a Lua global the generated code calls (`table`, `string`, `type`, `pairs`, `formatStr`, ...), or the name of a helper the module uses | The name with a trailing underscore: `end_`, `table_` | Applies to locals, parameters and private members; public members are fields of the module table and keep their names. |
| Local or parameter named like a private method or static field of the file, a member of the plugin class, or a class, that the same method uses: `int step = n - 1; return step(step);` in a private method `step` | The local with a trailing underscore, `local step_ = n - 1` | Java tells a method's locals from the file's members, but in Lua all of them are locals, so the local would hide the member. A local whose method does not use the member keeps its name. |
| Identifier with non-ASCII letters | *Never*: "Lua names are ASCII only; rename `größe`." | |

## Classes and members

| Java | Lua | Notes |
| --- | --- | --- |
| Class with only static members | `local Row = {}` ... `return Row` | |
| `public static` method | `function Row.complete(row)` | |
| `private static` method | `local function helper(...)` | Members keep their source order, except that code which runs while the module loads (a field's value, an initializer) comes after the functions it calls. A local that a function uses above its definition, as in mutual recursion, is declared ahead: `local isOdd`, with a comment. |
| `public static` field | `Row.LIMIT = 5` | |
| `private static` field | `local LIMIT = 5` | |
| Static initializer block | Statements at module level, in source order | |
| Class with instances | `local Prototyping = {}`, `Prototyping.__index = Prototyping` | The house `setmetatable` pattern. |
| Constructor `new Prototyping(a)` | `function Prototyping.new(a)` with `local self = setmetatable({}, Prototyping)` ... `return self` | Field initializers run first, then the constructor body. Several constructors only as a default chain (see overloading). |
| Field without initializer | `self.count = 0`, `self.done = false`; reference fields are left `nil` | Java's defaults are made explicit, because `nil` is not `0`. |
| Public instance method | `function Prototyping:confirm(sprite)` | Called as `tracker:confirm(sprite)`. |
| Private instance method | `local function rebuild(self, ...)` | Called as `rebuild(self, ...)`. |
| `this.x`, `x` (field) | `self.x` | |
| `class B extends A` | `local B = setmetatable({}, { __index = A })`, `B.__index = B` | `A` is a class of the plugin's; extending a library class is *Never*: "Lua has none of ArrayList's Java code to inherit; keep one in a field instead of extending it." Single inheritance only, which is all Java has. |
| `super(x)` | `local self = setmetatable(A.new(x), B)` | Statements before it, which Java 25 allows when they leave `this` alone, come before `local self`, as they run before the object exists. Without one, `A.new()`. |
| `super.method(y)` | `A.method(self, y)` | |
| Abstract method, interface of abstract methods | Nothing; they are types only | |
| Interface with constants, default or static methods | A table: `Describable.OPENING = "a"`, `function Describable:describe() ... end`, `function Describable.shout(thing) ... end` | A class copies each default method it does not override, `Herbal.describe = Describable.describe`, unless its superclass took it already. A default method called on a value of an interface no class implements, whose values are Lua functions, is *Never*: "No class of the plugin's implements Step, so its values are Lua functions, which carry no methods; ..." |
| `implements Comparable<T>`, `Runnable`, `Consumer<T>` and other library interfaces | The class as written, with `__lt` for `Comparable`, `function Herbal.__lt(a, b) return a:compareTo(b) < 0 end`, and `__call` for an interface marked `@FunctionalInterface`, `function Planter:__call(...) return self:accept(...) end` | So `table.sort` orders the objects by `compareTo`, and an object stands where Lua expects a function. An object enum orders by ordinal. `Iterable` and `Iterator` are *Never*: "Lua's for loop walks tables, not iterator objects; give the class a method that returns its list, and loop over that." |
| An interface of the plugin's with one abstract method that no class of the plugin implements | Its values are plain functions: lambdas and method references, called as `check(x)` | As for the JDK's functional interfaces. |
| An interface that a class of the plugin implements | Its values are objects: `shape:area()` | A lambda for it is *Never*: "Area is implemented by a class, so its values are Lua objects with methods, which a function cannot stand in for; implement it with a class here too." |
| `toString()` override | A normal method `function Point:toString()` | String concatenation with the object calls it explicitly; no metamethods. |
| Static nested class | `local Cell = {}` in the same file, before the outer class's members; unless it is private, also a field of the outer class's table, `Board.Cell = Cell` | Other files use it as `Board.Cell`. |
| Private members and nested classes of the classes in one file | Locals of the file | Two with the same name are *Never*: "In Lua, the private members and nested classes of the classes in a file are locals of one scope, so two of them cannot both be named helper; rename one." |
| Simple enum `enum Mode { FAST, SAFE }`, top-level or nested | `local Mode = { FAST = "FAST", SAFE = "SAFE" }` | Constants are their names, which reads well in logs; comments on a constant go above it in the table. `name()` and `toString()` return the constant itself, `values()` becomes the list `{ Mode.FAST, Mode.SAFE }`, and `valueOf(s)` becomes `Mode[s]`. An enum whose `ordinal()` or `compareTo` any code calls, or that code sorts in natural order (`Collections.sort`, `sorted()`, a `TreeSet`, `Comparator.naturalOrder()`), is an object enum instead, below, because its strings would sort by name. |
| Enum with fields, constructors, methods, constant bodies or interfaces, or whose order code reads | An object enum: a class whose constants are its instances, `Herb.GUAM = Herb.new("GUAM", 0, 5291, 9)`, with `Herb.new(name, ordinal, ...)` keeping `self.name` and `self.ordinal` | A field named `name` or `ordinal` is rejected: "In Lua an enum constant keeps its name and ordinal in the fields name and ordinal; rename the field name, for example to label." Constants work as map keys, in a `switch`, and with `values()` and `valueOf`. |
| A constant's body, `RANARR(5295) { @Override String describe() { ... } }` | `function Herb.RANARR:describe() ... end` | The body's methods replace the enum's on that constant only, and its fields are the constant's own, `Herb.RANARR.note = ...`. A private method there is *Never*: "A private method of an enum constant's body would be a local of the whole file in Lua, which another constant's could hide; make it a method of the enum." |
| `herb.name()`, `herb.ordinal()`, `a.compareTo(b)`, `"" + herb` | `herb.name`, `herb.ordinal`, `a.ordinal - b.ordinal`, `herb.name` | `toString()` is the enum's own when it has one. A setting of an object enum is kept by its name. |
| Record whose components are its only members, top-level or nested | A plain Lua table, built where the record is constructed: `new Position(id, true)` becomes `{ spriteId = id, visible = true }`, and `p.spriteId()` becomes `p.spriteId` | The record itself produces no Lua, so records also describe tables that Lua code passes in. See [Calling existing Lua](#calling-existing-lua). |
| Record with methods, constructors, static members or interfaces | An object record: a class whose fields are its components, `Tile.new(x, y, plane)` | A compact constructor's code runs before the components are assigned, as in Java. Accessor calls are field reads, `tile.x`. The `equals`, `hashCode` and `toString` javac gives a record stay *Never*, below; ones the record declares are its methods. |
| `equals`, `hashCode` or `toString` of a record | *Never*: "A record is a plain Lua table, compared by identity and without methods; use its components instead." | |
| Lambda `x -> x + 1`, block lambda | `function(x) return x + 1 end` | Java captures only effectively final variables, so Lua's by-reference capture behaves the same. An unnamed parameter, `_`, stays `_`, except at the end, where it is left out as Lua drops the arguments a function does not name: `(_, x, _) -> ...` is `function(_, x) ... end`, and `(_, _, _) -> ...` is `function() ... end`. |
| Functional interface call `pred.test(x)` | `pred(x)` | Any interface with one abstract method. |
| Method reference `Row::complete` (static) | `Row.complete` | |
| Method reference `this::onTick` | `function(event) self:onTick(event) end`; in the plugin class, the local function `onTick` | The function takes the parameters the method names. |
| `Herb::describe`, `Tile::x`, `planter::accept`, `Tile::new` | `function(herb) return herb:describe() end`, `function(tile) return tile.x end`, `function(herb) return planter:accept(herb) end`, `Tile.new` | A method of one object reads that object when the function runs, so it must be a name or field: "Java reads this value once, where the reference is, but Lua's function would read it each time it runs; store it in a local first." A plain record's constructor builds its table. |
| `Chat::AddMessage`, `npc::GetName`, `NPCEntity::GetName` | `Chat.AddMessage`, `function() return npc:GetName() end`, `function(npc) return npc:GetName() end` | A client function stands as it is. |
| `String::length`, `String::isEmpty`, `String::toUpperCase`, `Integer::parseInt`, `Objects::nonNull`, `Math::max`, `System.out::println`, `list::add`, `set::contains`, `map::get`, `ArrayList::new` | `function(text) return #text end`, ..., `math.max`, `print`, `function(value) list[#list + 1] = value end`, ... | Where a loop or stream calls a reference's small function, it is opened in place: `items.forEach(copy::add)` is `copy[#copy + 1] = item`, and so is a stream's `forEach(copy::add)`. `list::add` and `set::add` assign, which gives no value, so they work only where nothing reads one, as in `forEach`; elsewhere, as in `filter(seen::add)`, they are *Never*: "In Lua, seen::add is an assignment, which gives no value, where Java's add tells whether the collection changed; so it works only where nothing reads that, as in forEach(seen::add). Write a lambda here, which can test the collection first." Any other library method is *Never*: "Oneiro has no Lua function for this method reference; write it as a lambda, x -> ..., which it lowers in place." |
| Generic types and methods | Types disappear; code is unchanged | |
| `x instanceof Square`, `x instanceof Square s` | `getmetatable(x) == Square`; `isInstance(x, Monster)` for a class others extend | The pattern's variable stands for `x`, which must read the same each time: "The pattern's variable stands for this value, which Lua would read again each time; store it in a local first." An interface is the classes that implement it; one whose values are functions is `type(x) == "function"`. |
| `x instanceof String`, `Integer`, `Double`, `Boolean`, `Mode` | `type(x) == "string"`, `math.type(x) == "integer"`, `math.type(x) == "float"`, `type(x) == "boolean"`, `Mode[x] == x` | A simple enum's constant is one of its strings. |
| `instanceof` a plain record, a client type, a list, map or array | *Never*: "A record with only its components is a plain Lua table, which Lua cannot tell from other tables; ..." and "Lua cannot tell the client's objects, or lists, maps and other tables, apart by type; test what the value holds instead." | |
| Record patterns, `x instanceof Point(int a, int b)` | *Never*: "Lua has no patterns that take a record apart; test the type, then read the record's components." | |
| Overloads that form a default chain: each shorter one only calls the next longer one, passing its own parameters first and constants for the rest (`Point() { this(0, 0); }`, `add(x) { add(x, STEP); }`) | One function that fills in the missing arguments: `y = y or 0`, or for a boolean `if flag == nil then flag = false end` | The idiomatic Lua form of optional arguments; `---@param y? integer` marks them optional. Constants are literals, enum constants and static final fields; a `null` constant leaves the parameter nil. A filled-in parameter must not accept `null`, since a passed `nil` would get the default. The overloads are all static or all instance, all private or none; comments above the shorter ones go above the function. |
| Any other overloaded methods or constructors | *Never*: "Lua functions have no parameter types, so two methods cannot share a name; rename one (for example addAll, addOne), or make the shorter ones a default chain (see https://github.com/adelheidsoftware/oneiro/blob/main/docs/java-subset.md)." | For constructors: "...so a class has one constructor; turn the others into static methods with names of their own, or make the shorter ones a default chain". |
| A method or constructor with varargs, `int total(int... numbers)` | `function Grids.total(...)` with `local numbers = { ... }` | `---@param ... integer`. A call passes its values one by one; an array passed for them is spread, `Grids.total(table.unpack(more))`. |
| A field and a public method with the same name (`count` and `count()`) | *Never*: "The field count and the method count() would share one slot in the Lua table; rename one." | An object's fields and its class's methods are looked up in the same Lua table. |
| Inner (non-static) class | A class whose objects keep their outer object, `function Tab.new(outer, name)` with `self.outer = outer` | The outer object's members are read through it, `self.outer.owner`, `self.outer:tabCount()`, and `Bank.this` is `self.outer`. `new Tab(name)` passes the object making it, `Tab.new(self, name)`, and `bank.new Tab(name)` passes `bank`. |
| Instance initializer block `{ ... }` | Statements of `Class.new`, among the field initializers | In source order, as Java runs them. |
| Class declared inside a method | Statements of that function: `local Count = {}`, then its constructor and methods, which read the method's locals as upvalues | A private method or nested type there is *Never*: "A class inside a method is a local table whose members are its fields; make this a member the class's methods call on self, or move the class beside its outer one." So is reading the enclosing object's members from it. |
| Anonymous class of a functional interface, with its one method, no fields and no `this` | A function, as a lambda: `function(a, b) return #b - #a end` | Where a comparator, loop or stream calls it, it is opened in place. |
| Any other anonymous class | A table of its fields and methods, `{ heardCount = 0, heard = function(self, word) ... end }`; for a subclass, `withMembers(Shape.new("square"), { ... })` | The interfaces' default methods it does not replace are copied in. Its methods take `self` for their own object, so reading the enclosing object's members from them is *Never*: "An anonymous class's methods take self for their own object, so they cannot read the outer object's members as well; read what they need into a local before the class, which its methods can use." A private method there, and fields with a functional library interface, are *Never* too. |
| Reflection, `getClass()`, `hashCode()` | *Never*: "Lua has no runtime type information to match." | |

## Statements

| Java | Lua | Notes |
| --- | --- | --- |
| `int x = 1;`, `var y = f();` | `local x = 1`, `local y = f()` | `int a = 1, b = 2;` becomes two lines. |
| `int x;` | `local x` | |
| `x += 2;`, `s += "!";` | `x = x + 2`, `s = s .. "!"` | All compound operators. A string built up in a loop reads better as a `StringBuilder`. |
| `i++;` as a statement | `i = i + 1` | |
| `i++` or an assignment inside an expression | *Never*: "Lua has no assignment expressions; put `i++` on its own line." | |
| `if` / `else if` / `else` | `if` / `elseif` / `else` | |
| `switch` with `case A ->` or with `break` after each case | An `if` / `elseif` chain, with `default` as the `else` wherever it stands | `case A, B` and empty cases that share statements become `x == A or x == B`; a case of an enum compares with `Mode.FAST`. The selector goes into a `local` when it has a call, which Java evaluates once, or when its Lua is more than a name or field and several cases compare it; a getter names it (`local kind = event:getKind()`), otherwise it is `selected`. A comment above a case opens its branch. A type pattern is its type test, `case Monster m when m.level > 100` is `elseif isInstance(creature, Monster) and creature.level > 100 then`, with the variable standing for the selector; a pattern every value matches is the `else`. |
| `switch` expression | As the value of a `local`, an assignment or a `return`: the `if` chain assigning or returning each case's value | A `yield` ends its case, or each branch of an `if` at its end; an earlier `yield` is *Never*: "In Lua each case's value is assigned or returned at its end, which a yield in the middle cannot reach; move the yield to the end of its case, or of each branch of an if there." Anywhere else *Never*: "In Lua a switch is a statement; assign the switch to a local, or return it, instead of using it inside an expression." |
| Fall-through between non-empty cases | *Never*: "Lua has no fall-through; repeat the shared code or move it into a method." | |
| A `break` before the end of a case | *Never*: "Lua has no switch to leave early, and a break there would leave the enclosing loop instead; use if/else inside the case." | |
| `while` | `while` | |
| `do { ... } while (c);` | `repeat ... until not (c)` | |
| `for (int i = a; i < b; i++)` | `for i = a, b - 1 do` | With a literal bound the subtraction is folded: `i < 10` becomes `9`. `<=`, `>`, `>=`, `i--` and `i += k` map to the bound and step. The loop variable must not be assigned in the body, and the bound must not change during the loop; otherwise the loop becomes a `while`. |
| `for (int i = 0; i < list.size(); i++)` | `for i = 1, #list do` with `list.get(i)` as `list[i]` | When the body uses `i` only as an index of lists and arrays, in `i + 1` (which becomes `i`), or compared with another loop variable that counts from 1; otherwise `for i = 0, #list - 1 do` with `list[i + 1]`. |
| Any other `for` | `local` init, `while`, update at the end of the body | |
| `for (T x : list)`, arrays too | `for _, x in ipairs(list) do` | |
| `for (var e : map.entrySet())` | `for key, value in pairs(map) do` | `e.getKey()` and `e.getValue()` become `key` and `value`. |
| `for (K k : map.keySet())`, `map.values()`, `set` | `for k in pairs(map) do`, `for _, v in pairs(map) do`, `for x in pairs(set) do` | Iteration order is unspecified in both languages. |
| `break` | `break` | |
| `continue` | `goto continue`, with `::continue::` as the last statement of the loop body | Lua has no `continue`; this is the one use of `goto` the house style allows. |
| Labeled `break` or `continue` | *Never*: "Lua's break and continue only reach the innermost loop; move the loops into a method and return from it." | |
| `return x;` | `return x` | |
| `throw new IllegalStateException("bad " + x);` | `error(formatStr("bad {}", x))` | Any exception type; only the message survives. Without a message, the class's simple name is the message. Lua puts the place an error was raised in front of its message. |
| `throw e;` of a caught error | `error(e, 0)` | Raised again unchanged, so its message keeps the place it was first raised. |
| `RuntimeException e = new RuntimeException(msg); ... throw e;`, `e.getMessage()` | `local e = msg`, `error(e)`, `e` | An exception kept in a local is its message, the value `error` raises. |
| `try { ... } catch (RuntimeException e) { ... }` | `local ok, e = pcall(function() ... end)`, then `if not ok then ... end` | `Exception` and `Throwable` work too: each catches every Lua error. `e.getMessage()` is the error value, `e.toString()` is `tostring(e)`, and `e` is named only when the catch block reads it. See [Errors](#errors). |
| A `try` block that returns | `local ok, result = pcall(function() ... return x end)`, then `if ok then return result else ... end` | Every path through the block must return: "In Lua the try block is a function that pcall runs, and Lua cannot tell a function that returned nothing from one that ran to its end; make every path through the try block return, or none." |
| A `catch` of another exception class | *Never*: "Lua errors carry a value, not a class, so a catch cannot tell exception classes apart; catch RuntimeException, Exception or Throwable, and tell the errors apart inside it." | |
| Several `catch` clauses | *Never*: "Lua errors carry a value, not a class, so one catch takes every error; catch RuntimeException once, and tell the errors apart inside it." | |
| `break`, `continue` or `yield` that leaves a `try` block | *Never*: "Lua runs the try block as a function, which a break, continue or yield cannot leave; set a flag and act on it after the try." | |
| `finally` | The try block, and a catch block, each in a function `pcall` runs, then the finally block, then `if not ok then error(result, 0) end` | So the finally block runs whatever the others do, and an error neither handled goes on unchanged. A value the try or catch block returns is returned after the finally block; when only one of them returns, a local `returned` says which. |
| `try (Reader r = open()) { ... }` | `local r = open()`, the try block in `pcall`, then `r:close()`, before any catch or finally of the statement's own | Resources close in reverse order, as in Java. A resource is a class of the plugin's with `close()`. |
| `assert c : "m";` | *Never*: "Java assertions are off by default, but Lua's assert always runs; write if (!condition) throw new IllegalStateException(\"...\") instead." | |
| `synchronized` block | *Never*: "Plugins run on one thread, and Lua has no threads to synchronise; remove the synchronized block." | The modifiers `synchronized` on a method and `volatile` on a field are accepted, and change nothing on one thread. |
| Threads, executors, futures and timers: `new Thread(...)`, `Thread.sleep`, `Executors`, `CompletableFuture`, `java.util.Timer` | *Never*: "Plugins run on the client's one thread and cannot start threads or schedule work of their own; do the work in an event callback, and to do it later, count the ticks of Event.ServerLogic, about every 0.6 seconds, or of Event.Logic, 50 a second." | |
| Reading the time: `System.currentTimeMillis()`, `System.nanoTime()`, `Instant.now()` and the other `now()` methods | *Never*: "The client gives plugins no clock to read; to measure time, count the ticks of Event.ServerLogic, about every 0.6 seconds, or of Event.Logic, 50 a second." | Lua's `os` library, which has the clock, is not in the client. Some payloads carry a time, such as `ChatMessage`'s `timestamp`. |
| `System.out.println(x)` | `print(x)` | A value that is not a `String` is converted as in [Converting values to text](#converting-values-to-text). |

### Errors

Lua errors are values, usually a message, with no class. `throw new X(message)` becomes `error(message)`, which puts
where the error was raised in front of a string message (`src/core/search.lua:57: search: node budget spent`), and
`try`/`catch` becomes `pcall`. So one catch clause takes every error, and the catch block tells errors apart by their
message: compare with `contains`, since an exact `equals` would also have to match the place.

```java
try {
    return search(depth);
} catch (RuntimeException e) {
    if (e.getMessage().contains(NODE_BUDGET_SPENT)) {
        return null;
    }
    throw e;
}
```

```lua
local ok, result = pcall(function()
    return search(depth)
end)
if ok then
    return result
else
    local e = result
    if e:find(NODE_BUDGET_SPENT, 1, true) ~= nil then
        return nil
    end
    error(e, 0)
end
```

## Expressions

| Java | Lua | Notes |
| --- | --- | --- |
| `42`, `42L`, `1_000`, `0xFF` | `42`, `42`, `1000`, `0xFF` | Binary and octal literals become decimal. |
| `1.5`, `1.5f`, `1e3`, `2.0` | `1.5`, `1.5`, `1e3`, `2.0` | `float` is a 64-bit double in Lua. |
| `"text\n"`, `"é"` | `"text\n"`, `"é"` | Letters outside ASCII stay as written; invisible characters such as a zero-width space are escaped, `"\u{200b}"`. |
| Text block `"""` | Long string `[[ ... ]]`, with `=` levels if the text contains `]]` | |
| `null`, `true`, `false` | `nil`, `true`, `false` | |
| `char c = 'a';` | `local c = "a"` | A char is a one-character string. A Lua string holds bytes, so a char is one byte: the ASCII characters behave as in Java, and others stay as written but have no one number. Two chars compare as the strings they are, `c >= "a"`, which orders ASCII as Java does. |
| `c - 'a'`, `(int) c`, `int n = c;`, `c < 65`, `counts[c]` | `string.byte(c) - string.byte("a")`, `string.byte(c)`, ... | Wherever Java reads a char as its number: in arithmetic, compared with a number, as an index, for an `int` parameter, variable or return value, and in a cast. A literal outside ASCII is rejected there: "A Lua string holds bytes, and 'é' takes several, so Lua has no one number for it; ..." |
| `(char) n`, `char c = 65;`, `c++`, `c += 2` | `string.char(n)`, `"A"`, `c = string.char(string.byte(c) + 1)`, ... | A literal number becomes its character. |
| `new char[n]`, a `char` field without a value | `filled(n, "\000")`, `"\000"` | Java's default char. |
| `for (char c : s.toCharArray())` | `for c in s:gmatch(".") do` | The text itself, one character at a time. A loop variable of a number type over chars is *Never*: "Each char would reach this loop as a one-character string, not as its number; loop with a char variable and convert it with (int) where the number is needed." |
| `Character.isDigit(c)`, `isLetter`, `isLetterOrDigit`, `isWhitespace`, `isUpperCase`, `isLowerCase` | `c:find("%d") ~= nil`, `%a`, `%w`, `%s`, `%u`, `%l` | Lua's character classes, which know ASCII only. A number argument, a code point, is `string.char(n)` first. |
| `Character.toUpperCase(c)`, `toLowerCase(c)`, `Character.toString(c)` | `c:upper()`, `c:lower()`, `c` | |
| `Character.getNumericValue(c)`, `Character.digit(c, radix)` | `tonumber(c, 36) or -1`, `tonumber(c, radix) or -1` | `tonumber` reads the letters after 9 as digits, `a` as 10, as Java does. |
| `+ - *` | `+ - *` | |
| `/` on `double`, or mixed | `/` | |
| `/` on integers | `a // b` when both operands are known to be non-negative, otherwise `quotient(a, b)` | Known non-negative: literals, `size()`, `length()`, `.length`, `Math.abs(...)`, indexes of a numeric loop counting up from a non-negative start; constants, and private or final integer fields and locals that start non-negative and are only ever raised (`++`, or `+=`, `*=`, `/=`, `%=` or `=` of a non-negative value), such as a tick counter; and calls of a method of the same file that nothing overrides and whose every `return` is non-negative. Lua's `//` rounds down and Java's `/` rounds toward zero; they agree for non-negative operands. |
| `%` | `a % b` when both operands are known to be non-negative, otherwise `math.fmod(a, b)` | `math.fmod` keeps the sign of `a`, as Java's `%` does. |
| `Math.floorDiv(a, b)`, `Math.floorMod(a, b)` | `a // b`, `a % b` | |
| `& \| ^ ~ <<` | `& \| ~ ~ <<` | |
| `a >> n` | `a >> n` when `a` is known to be non-negative, otherwise `a // (1 << n)` | Lua's `>>` is a logical shift; floor division by a power of two is Java's arithmetic shift. With a literal `n` the divisor is folded (`a // 16`). |
| `a >>> n` on `int` | `(a & 0xFFFFFFFF) >> n` | On `long`, plain `a >> n`. |
| `== != < <= > >=` | `== ~= < <= > >=` | On objects, `==` compares identity in both languages. |
| `&& \|\| !` | `and or not` | A negated equality flips instead: `!(a == b)` and `!set.contains(x)` become `a ~= b` and `set[x] == nil`. |
| `&` and `\|` on booleans | `and`, `or` when the right side has no calls | Otherwise *Never*: "Lua evaluates both sides only through `and`/`or`; evaluate the right side into a local first." |
| `c ? a : b` | `c and a or b` when `a` can be neither `false` nor `null` | Otherwise, as the value of a `return`, assignment or `local`, an `if` / `else` assigning or returning each branch; anywhere else *Never*: "assign the conditional to a local first". |
| String concatenation | See [Building strings](#building-strings) | |
| `new Prototyping(a)` | `Prototyping.new(a)` | |
| `obj.method(a)`, `Row.complete(r)` | `obj:method(a)`, `Row.complete(r)` | |
| `(int) d` from a `double` | `toInteger(d)` | Java truncates toward zero; Lua's `math.floor` rounds down. |
| Widening casts, `(Prototyping) obj` | Nothing | Casts to classes are not checked in Lua. |
| `int[] a = {1, 2};`, `new int[] {1, 2}` | `local a = { 1, 2 }` | |
| `new int[n]`, `new boolean[n]` | `filled(n, 0)`, `filled(n, false)` | Java fills new arrays with zeros. |
| `new int[rows][columns]`, and more dimensions | `grid(0, rows, columns)` | A helper that makes each row a table of its own. `new int[rows][]` is full of null, which a Lua array cannot hold. |
| `new String[n]` and other reference arrays | *Never*: "a new reference array is full of `null`, and Lua arrays cannot hold `nil`; use a `List` and add elements." | |
| `a[i]`, `a.length` | `a[i + 1]`, `#a` | Indexes stay 0-based in the source. |
| `Integer.MAX_VALUE`, `Long.MAX_VALUE` | `2147483647`, `math.maxinteger` | |

## Strings

| Java | Lua | Notes |
| --- | --- | --- |
| `s.length()`, `s.isEmpty()` | `#s`, `s == ""` | Lua counts bytes; the same for ASCII. |
| `s.equals(t)` | `s == t` | Lua compares strings by value; Java's `s == t` also becomes `s == t`. |
| `s.equalsIgnoreCase(t)` | `s:lower() == t:lower()` | |
| `s.substring(a)`, `s.substring(a, b)` | `s:sub(a + 1)`, `s:sub(a + 1, b)` | |
| `s.contains(t)` | `s:find(t, 1, true) ~= nil` | Plain search, no patterns. |
| `s.indexOf(t)`, `s.indexOf(c)` | `stringIndexOf(s, t)` | Returns -1 when absent, as in Java. A char is the one-character string to find. |
| `s.startsWith(p)` | `s:sub(1, #p) == p` | |
| `s.endsWith(p)` | `s:sub(-#p) == p` when `p` is a non-empty literal, otherwise `endsWith(s, p)` | |
| `s.toUpperCase()`, `s.toLowerCase()` | `s:upper()`, `s:lower()` | ASCII letters only in Lua. |
| `s.trim()`, `s.strip()` | `trim(s)` | |
| `s.repeat(n)` | `s:rep(n)` | |
| `String.valueOf(x)`, `Integer.toString(n)`, `Double.toString(d)` | The value as text, by its type | See [Converting values to text](#converting-values-to-text): `tostring(n)` for integers, `formatStr("{}", d)` for doubles. |
| `Integer.parseInt(s)` | `tonumber(s, 10)` | Invalid input gives `nil` instead of an exception. |
| `matches`, `replaceAll` and other regular-expression methods, `Pattern` and `Matcher` | *Never*: "Lua has no regular expressions; use LuaString.match, find, gsub or gmatch with a Lua pattern." | See [Lua patterns](#lua-patterns). |
| `line.split(",")`, `line.split("\\|", 2)` | `split(line, ",")`, `split(line, "|", 2)` | A helper, with Java's rules: no limit drops trailing empty parts, a negative one keeps them, a positive one makes at most that many parts, and text without the separator is one part. Only a separator that stands for itself; a real pattern is *Never*: "Lua has no regular expressions, so split takes only a separator that stands for itself, such as a comma or an escaped bar; for a pattern, use LuaString.gmatch." |
| `s.charAt(i)` | `s:sub(i + 1, i + 1)` | `s:sub(i, i)` in a loop that counts from 1. The index appears twice, so one whose Lua calls a function is rejected (`s.length() - 1` is `#s`, no call): "Lua would evaluate this index twice; store it in a local first." |
| `s.toCharArray()`, `new String(chars)`, `String.valueOf(chars)` | `characters(s)`, `table.concat(chars)` | A helper makes the list of one-character strings. |
| `s.chars()`, `s.codePointAt(i)` | *Never*: "A Lua string has no stream of its characters; loop over text.toCharArray(), which walks the text itself." and "A Lua string holds bytes, not code points; (int) text.charAt(i) is the byte's number, which is the code point for ASCII text." | |

### Lua patterns

Lua has no regular expressions. It has patterns of its own, which `org.adelheid.oneiro.api.LuaString` offers: each
of its functions is Lua's string function of the same name, and its Javadoc describes the pattern syntax, in which
`%d` is a digit and `%.` a literal point.

| Java | Lua | Notes |
| --- | --- | --- |
| `LuaString.match(line, "^You catch an? (.+)%.$")` | `line:match("^You catch an? (.+)%.$")` | The first capture, or the whole match when the pattern has none; null when nothing matches. |
| `LuaString.captures(line, "^Drop: (%d+) x (.+)$")` | `{ line:match("^Drop: (%d+) x (.+)$") }` | Every capture of the first match, in a list; empty when nothing matches. |
| `LuaString.find(line, "%d")` | `(line:find("%d") or 0) - 1` | Where the first match starts, counted from 0; -1 when nothing matches. |
| `LuaString.gsub(line, "%d+", "#")` | `(line:gsub("%d+", "#"))` | `%1` to `%9` in the replacement stand for the captures. The parentheses keep only the text, not the count Lua's `gsub` also gives. |
| `for (String word : LuaString.gmatch(line, "%a+"))` | `for word in line:gmatch("%a+") do` | Each match's first capture, or the whole match. Lua gives the matches through an iterator, so anywhere but a for loop it is *Never*: "LuaString.gmatch gives its matches one at a time to a for loop; loop over it, or read the first match's captures with LuaString.captures." |

### Building strings

RS3 plugins build strings with the client's `formatStr` (`{}` placeholders, fmtlib options), which the house style
prefers over chains of `..`, and Lua builds strings piece by piece in a table joined by `table.concat`. Java's ways of
building strings map onto those two.

| Java | Lua | Notes |
| --- | --- | --- |
| `"Blueprint " + name + " at level " + level` | `formatStr("Blueprint {} at level {}", name, level)` | A concatenation with literal text or a non-`String` operand becomes one `formatStr` call; braces in the text are doubled (`{{`). |
| `a + b` where every operand is a `String` | `a .. b` | The template would be only placeholders, so `..` reads better. |
| `"a" + "b"` | `"ab"` | Literals are folded. |
| `new StringBuilder()`, `new StringBuilder(s)` | `local sb = {}`, `local sb = { s }` | A table of parts. |
| `sb.append(x)`, chained `sb.append(a).append(b)` | `sb[#sb + 1] = x`, one line per `append` | Each value is converted as below; an appended concatenation becomes `formatStr`. |
| `sb.toString()`, `"" + sb` | `table.concat(sb)` | `"" + x` is the value as text, as `String.valueOf(x)` gives it. |
| `sb.length()`, `sb.charAt(i)`, `sb.isEmpty()`, `sb.indexOf(s)` | `#table.concat(sb)`, `table.concat(sb):sub(i + 1, i + 1)`, ... | The builder's pieces joined, then read as a string. |
| `sb.setLength(0)`, `reverse()`, `insert(i, x)`, `deleteCharAt(i)`, `delete(a, b)`, `setCharAt(i, c)` | `builderSet(sb, "")`, `builderSet(sb, table.concat(sb):reverse())`, ... | A helper puts the changed text back as the builder's one piece; the edits that cut the text take it into a local `text` first. Each is a statement. |
| `String.join(", ", list)` | `table.concat(list, ", ")` | |
| `String.format("%d of %s", a, b)` | `formatStr("{} of {}", a, b)` | The format must be a literal; its specifiers translate as below. |

#### Converting values to text

The client hands every number to `formatStr` as a double (see [Client findings](#client-findings)). For `int` values
and for doubles that is exact, and `{}` prints a double in its shortest exact form, as Java does
(`0.30000000000000004`). A `long` can exceed what a double holds exactly, and `table.concat` and `print` use Lua's
shorter number format, so each value is converted by its Java type:

| Java type | Inside `formatStr` | In `table.concat` and `print` |
| --- | --- | --- |
| `String` | as is | as is |
| `char` | as is | as is |
| `int`, `short`, `byte` | as is | as is |
| `long` | `tostring(n)` | as is |
| `double`, `float` | as is | `formatStr("{}", d)` |
| `boolean` | as is | `tostring(b)` |
| `null` | as is; prints `nil` | `tostring(x)` |
| Plugin object | `obj:toString()` | `obj:toString()` |
| RS3 API object (`CoordGrid`, vectors) | `tostring(v)` | `tostring(v)` |
| `List`, `Map`, `Set`, and their `toString()` | `listText(list)`, `mapText(map)`, `setText(set)` | Helpers that write `[a, b]` and `{k=v}` as Java does, for elements that are strings, numbers or booleans; a map's or set's order is Lua's, as unfixed as a `HashMap`'s. Other elements are *Never*: "Lua would write each X as its table address, not as Java's toString(); join the elements with String.join or a loop." |

#### `String.format` specifiers

| `String.format` specifier | `formatStr` option | Notes |
| --- | --- | --- |
| `%s`, `%d` | `{}` | Arguments are converted as above. |
| `%.3s` | `{:.3}` | |
| `%f`, `%.2f`, `%e` | `{:f}`, `{:.2f}`, `{:e}` | Integer arguments work too, because the client formats every number as a double. |
| `%5s`, `%-5s`, `%5d` | `{:>5}`, `{:<5}`, `{:>5}` | Alignment is always written out: the client aligns strings left by default, Java aligns everything right. |
| `%05d`, `%+d`, `% d`, `%08.3f` | `{:05}`, `{:+}`, `{: }`, `{:08.3f}` | |
| `%x`, `%X`, `%o`, with width and flags | `{}` with the argument formatted by Lua: `string.format("%x", n)` | The client refuses integer options. An `int` argument is masked (`n & 0xFFFFFFFF`) so negative values print as Java's 32-bit form. A `long` argument with `%d` and a `0`, `+` or space flag goes the same way (`string.format("%+d", n)`) to stay exact. |
| `%1$s`, `%2$d` | `{0}`, `{1}` | When any specifier is numbered, all of them are, because fmtlib cannot mix the two. |
| `%%`, `%n` | `%`, `\n` | |
| `%g`, `%,d` | *Never*: "The client's formatStr drops trailing zeros for %g, which Java keeps; use %f or %e." and "The client's formatStr adds no digit grouping, so %,d cannot print as Java prints it." | |
| `%c`, `%C` | `{}` with the char, or `string.char(n)` for an `int` | `%C` adds `:upper()`. |
| `%b`, `%B` | `{}` with the boolean | `%B` is `tostring(b):upper()`. Only a boolean: Java's `%b` of anything else prints whether it is null, which is *Never*: "Java's %b of anything but a boolean prints whether it is null; pass that test, such as value != null, instead." |
| `%t...` | *Never*: "The client has no os library to read the calendar from, so %t has nothing to format; format the parts yourself with %d." | |

Standalone tests run outside the client, where `formatStr` does not exist, so the test harness defines a stand-in that
reproduces the [Client findings](#client-findings), including formatting every number as a double. It is test-only
code and never ships.

## Collections

Lists, maps and sets become plain Lua tables, so they can be passed to and from the RS3 API without conversion. Lua
tables cannot hold `nil`, which is the one genuine gap: a `null` element or map value is rejected where Oneiro can see
it, and documented everywhere else.

| Java | Lua | Notes |
| --- | --- | --- |
| `new ArrayList<>()`, `List.of(a, b)` | `{}`, `{ a, b }` | `List.of` is not made immutable. |
| `new ArrayList<>(other)` | `table.move(other, 1, #other, 1, {})` | A shallow copy. |
| `list.add(x)` | `list[#list + 1] = x` | |
| `list.add(i, x)` | `table.insert(list, i + 1, x)` | |
| `list.get(i)`, `list.set(i, x)` | `list[i + 1]`, `list[i + 1] = x` | With a literal index the addition is folded: `get(0)` becomes `list[1]`. `set` used as a value is *Never*. |
| `list.size()`, `list.isEmpty()` | `#list`, `#list == 0` | |
| `list.remove(i)` with an `int` index | `table.remove(list, i + 1)` | |
| `list.contains(x)`, `list.indexOf(x)` | `listContains(list, x)`, `listIndexOf(list, x)` | |
| `list.addAll(other)` | `table.move(other, 1, #other, #list + 1, list)` | |
| `list.sort(cmp)`, `Collections.sort(list)` | `table.sort(list, function(a, b) return ... end)`, `table.sort(list)` | Lua's sort is not stable; equal elements may change order. The comparator becomes Lua's "comes before" test, below. |
| `(a, b) -> Integer.compare(a.level, b.level)`, `(a, b) -> a.name.compareTo(b.name)`, `(a, b) -> a.x - b.x` | `a.level < b.level`, `a.name < b.name`, `a.x - b.x < 0` | A comparator lambda's parameters name the sort function's. |
| `Comparator.comparingInt(n -> n.level)`, `comparing`, `comparingDouble`, `naturalOrder()`, `reverseOrder()`, `Integer::compare`, `cmp.reversed()` | `a.level < b.level`, ..., `a < b`, `b < a`, `a < b`, the two swapped | The key function is a lambda with an expression body, or a method reference Oneiro can call. |
| `cmp.thenComparing(key)`, `thenComparingInt(key)`, `thenComparing(cmp2)` | `a.herb < b.herb or (a.herb == b.herb and a.count < b.count)` | After any other first comparator, the first one is tested both ways round. |
| `new HashMap<>()`, `Map.of(k, v)` | `{}`, `{ [k] = v }` | A string key that is a Lua name is written as one: `Map.of("a", 1)` becomes `{ a = 1 }`. |
| `map.put(k, v)`, `map.get(k)` | `map[k] = v`, `map[k]` | `put` used as a value is *Never*. |
| `map.getOrDefault(k, d)` | `map[k] or d` | On a map of `Boolean` values, where `or` would replace a stored `false`: `map[k] ~= false` for a default of `true`, `map[k] == true` for `false`, otherwise the helper `valueOr(map[k], d)`. |
| `map.containsKey(k)`, `map.remove(k)` | `map[k] ~= nil`, `map[k] = nil` | |
| `map.size()`, `map.isEmpty()` | `count(map)`, `next(map) == nil` | |
| `new HashSet<>()`, `set.add(x)`, `set.contains(x)`, `set.remove(x)` | `{}`, `set[x] = true`, `set[x] ~= nil`, `set[x] = nil` | `add` used as a value is *Never*. |
| `Set.of(a, b)` | `{ [a] = true, [b] = true }` | `Set.of("x")` becomes `{ x = true }`. |
| `set.size()` | `count(set)` | |
| Map keys or set elements of a record or of a class that overrides `equals` | *Never*: "Lua compares table keys by identity, so value-equal keys would not match; key the map by a string or number." | |
| `new LinkedHashMap<>()`, `new LinkedHashSet<>()`, `new TreeMap<>()`, `new TreeSet<>(cmp)` | `orderedTable()`, `sortedTable()`, `sortedTable(function(a, b) return ... end)` | Two commented helpers. The table holds its entries itself, so `m[k]`, `next` and every other map and set form work as on any table; its metatable's `__pairs` walks the keys in the order they were first added, or sorted, and passes over removed ones. A key removed and added again goes to the end, as in Java. `firstKey()`, `lastKey()`, `first()` and `last()` are the helpers `firstKey(t)` and `lastKey(t)`, nil where Java throws; `TreeMap`'s searches, such as `floorKey` and `headMap`, are not supported. An access-ordered `LinkedHashMap` is *Never*: "An access-ordered LinkedHashMap moves a key on every get, which Lua cannot see a table read do; keep the order yourself, in a List of keys." |
| `new ArrayList<>(list)`, `new ArrayList<>(set)`, `new ArrayList<>(map.values())` | `table.move(list, 1, #list, 1, {})`, `keysOf(set)`, `valuesOf(map)` | |
| `new HashSet<>(list)`, `new HashSet<>(set)`, `new HashMap<>(map)`, and the same for the ordered kinds | `addElements({}, list)`, `addKeys({}, set)`, `copyEntries({}, map)`, `copyEntries(sortedTable(), map)` | A sorted map or set copied into a `TreeMap` or `TreeSet` is *Never*, because Java keeps its comparator: "Java copies a sorted map or set with its comparator, which a Lua table does not keep; make the new one with that comparator, then add the entries." |
| `set.addAll(list)`, `list.addAll(set)`, `map.putAll(other)` | `for _, value in ipairs(list) do set[value] = true end`, ... | A loop over what the other collection holds; a list into a list is `table.move`. Each is a statement. |
| `list.getFirst()`, `getLast()`, `removeFirst()`, `removeLast()`, `addFirst(x)`, `addLast(x)` | `list[1]`, `list[#list]`, `table.remove(list, 1)`, `table.remove(list)`, `table.insert(list, 1, x)`, `list[#list + 1] = x` | Java 21's sequenced methods. |
| `LinkedList`, `ArrayDeque`, `Deque`, `Queue` | A Lua list, its first element at the front | `addLast`, `offer` and `add` append; `addFirst`, `offerFirst` and `push` are `table.insert(q, 1, x)`; `poll`, `pop` and `removeFirst` are `table.remove(q, 1)`, `pollLast` `table.remove(q)`; `peek` is `q[1]`, `peekLast` `q[#q]`. An empty queue gives nil, where Java's `remove()` and `element()` throw. |
| `list.forEach(x -> ...)`, `map.forEach((k, v) -> ...)` | `for _, x in ipairs(list) do ... end`, `for k, v in pairs(map) do ... end` | The lambda's code becomes the loop's body. A method reference, a function in a variable, or a block that returns early is called for each element. |
| `list.removeIf(x -> test)` | `for i = #list, 1, -1 do local x = list[i] if test then table.remove(list, i) end end` | From the end, so that removing skips nothing. On a set, or a map's `keySet()` or `values()`, a `pairs` loop that clears what passes. A statement. |
| `map.putIfAbsent(k, v)` | `if map[k] == nil then map[k] = v end` | A statement. |
| `counts.merge(k, 1, Integer::sum)`, `merge(k, s, String::concat)` | `counts[k] = (counts[k] or 0) + 1`, `... = (map[k] or "") .. s` | Any other merge, and `computeIfAbsent`, `compute` and `computeIfPresent`, are helpers of those names: `computeIfAbsent(groups, k, function(_) return {} end)`, which also give the value, so `groups.computeIfAbsent(k, x -> new ArrayList<>()).add(v)` works. |
| `Optional<T>`, `OptionalInt` | The value, or nil: `---@return string?` | An `Optional` has no Lua box: `Optional.of(x)` and `ofNullable(x)` are `x`, `Optional.empty()` is `nil`. |
| `opt.isPresent()`, `opt.isEmpty()`, `opt.get()` | `opt ~= nil`, `opt == nil`, `opt` | An empty one gives nil where Java's `get()` and `orElseThrow()` throw. |
| `opt.orElse(d)`, `opt.orElseGet(() -> d)` | `opt or d` | Lua's `or` evaluates `d` only when needed, as `orElseGet` does. On an `Optional<Boolean>`, where `or` would replace a `false`: `opt ~= false` for a default of `true`, as `getOrDefault` does. |
| `opt.orElseThrow(() -> new IllegalStateException("none"))` | `opt or error("none")` | |
| `opt.ifPresent(x -> ...)`, `ifPresentOrElse(x -> ..., () -> ...)` | `if opt ~= nil then local x = opt ... end`, with an `else` | A value that is a call goes into the lambda's own local first, `local x = find()`, or into `value` when that would hide another local. |
| `opt.map(...)`, `filter`, `flatMap`, `or`, `stream` | *Never*: "In Lua an Optional is its value or nil, with no box around it to map; test it with isPresent() and work on the value." | |
| `list.stream().filter(x -> test).map(x -> value).toList()` | `local result = {} for _, x in ipairs(list) do if test then result[#result + 1] = value end end` | One stream is one loop. A stream is the whole value of a local, which it is built in, an assignment or a return, or a `forEach` statement of its own; anywhere else it is *Never*: "In Lua a stream is a loop, which runs as statements; make the stream the whole value of a local, an assignment or a return, or a forEach statement of its own." Each lambda's parameter stands for the loop's element, so its code reads as written; a mapped value gets a local, named after the next lambda's parameter, when a later step reads it. |
| Sources: `list.stream()`, `set.stream()`, `map.keySet().stream()`, `map.values().stream()`, `Arrays.stream(a)`, `Stream.of(a, b)`, `IntStream.range(a, b)`, `rangeClosed` | `ipairs(list)`, `pairs(set)`, `pairs(map)`, `ipairs({ a, b })`, `for i = a, b - 1 do`, `for i = a, b do` | A stream of `entrySet()` is *Never*: loop over the entries with a for loop. |
| Stages: `filter`, `map`, `mapToInt`, `mapToObj` and kin, `boxed`; `sorted(cmp)` just before `toList()` | `if test then`, consecutive filters as one `if a and b then`; `table.sort` after the loop | A test or value is a lambda with an expression body, or a function to call. |
| Terminals: `forEach`, `toList()`, `collect(Collectors.toList())`, `toSet()`, `joining(sep)`, `count()`, `sum()`, `min`, `max`, `findFirst`, `anyMatch`, `allMatch`, `noneMatch` | The loop's last statements, and a local before it: `{}`, `0`, `false`, or nil until a first element | Returned, `anyMatch`, `allMatch`, `noneMatch` and `findFirst` (with `orElse`) return from inside the loop. `min` and `max` give their `Optional` as a value or nil, `max().orElse(0)` as `max or 0`. |
| `distinct`, `limit`, `skip`, `flatMap`, `reduce`, `groupingBy`, `average` and the rest | *Never*: "Oneiro makes one loop of a stream: a list, set, array, map view, Stream.of or IntStream.range, then filter and map, then forEach, toList, ... ; write a for loop for distinct." | |
| `Iterable` of the plugin's, `iterator()`, `hasNext()`, `next()`, `it.remove()` | *Never*: "Lua walks tables with its for loop, not iterator objects; loop over the collection itself, or use removeIf to remove while walking." | |

## The RS3 API

The RS3 API is called through Java bindings that `oneiroBindings` generates on the developer's machine from
runescape-api-stubs, in the package `org.adelheid.oneiro.rs3`. They mirror the Lua tables: one class per global table,
with nested classes for nested tables (`ui.Text`, `config.NPC`), and the Lua names, PascalCase methods included. Three
names change, because Java cannot use them: the `var` table is `vars`; the global functions (`log`, `formatStr`,
`ExecuteConsoleCommand`) are static methods of `Globals`; and the `Plugin` table is `CurrentPlugin`, because the
plugin class extends `org.adelheid.oneiro.api.Plugin`. Annotations on each binding say how Lua calls it, so the
plugin's Java needs no annotations of its own.

| Java | Lua | Notes |
| --- | --- | --- |
| `Player.GetEntity()`, `Event.Logic.Subscribe(name, f)` | `Player.GetEntity()`, `Event.Logic.Subscribe(name, f)` | A dot call where the stubs declare one. |
| `PersistentDB.GetInt("visits")`, `ui.Interfaces.GetComponent(id)` | `PersistentDB:GetInt("visits")`, `ui.Interfaces:GetComponent(id)` | A colon call on a table that is one object; Java calls it as a static method. |
| `text.SetPos(10, 20)`, `npc.isValid` | `text:SetPos(10, 20)`, `npc.isValid` | Methods and fields of the objects the client hands out. Read-only fields are `final`. |
| `new ui.Text(parent)`, `new CoordGrid(x, y, z)` | `ui.Text.new(parent)`, `CoordGrid.new(x, y, z)` | A Lua `new` is a Java constructor. |
| `new NPCListFilterSettings()` and its fields set right after | `{ coordGrid = tile, range = 5 }` | A table the plugin builds; the assignments that follow the constructor are folded into it. |
| `config.NPC.RS3_HANS`, `vars.VarPlayer.RS3_QUEST_POINTS.value` | `config.NPC.HANS`, `var.VarPlayer.QUEST_POINTS.value` | Config and var names come from the stubs' csvs. Every name of `id`, `config` and `vars` starts with `RS3_` in Java, so that Java can spell all of them, and the Lua drops it. |
| `id.Obj.RS3_COINS`, `case id.Obj.RS3_COINS ->` | `id.Obj.COINS` | `id` names are `int` constants, so they work as `case` labels. |
| `id.Seq.RS3_1ST4H_GUARD_GOBLIN_EAT` | `id.Seq["1ST4H_GUARD_GOBLIN_EAT"]` | A name that starts with a digit is not a Lua name either, so Lua indexes the table with it. |
| `GameState.GAME`, `ModifierKey.SHIFT` | `GameState.GAME`, `ModifierKey.SHIFT` | An enum the API uses as a type is a Java enum; bit flags are `int` constants. So is an enum whose values nobody has documented, since a Java enum lowers to the table's own field whatever its value. Values the client gives other than the stubs' come from the overrides' `values` lines (checked in the client, 2026-10-23). |
| `state.name()`, `"state " + state` | `GameState.FromID(state)`, `formatStr("state {}", GameState.FromID(state))` | A client enum's value is the client's number; the table's `FromID` gives its name, as Java writes it. Four Java enums have no `FromID`, `PersistentDBType`, `PluginPermissions`, `ShapeEntityAlignType` and `ui.AspectRatioMode`, and their values' names are *Never*: "The client's PluginPermissions table has no FromID to give a value's name; compare the value with PluginPermissions's constants instead." Other methods of a value, such as `ordinal()`, are *Never* too. |
| `SystemKey.ENTER` | `SystemKey.ENTER` | A second name for a value, as `ENTER` is for `RETURN` (both 13), is a constant equal to the first, so a `switch` cannot have both: Lua could not tell them apart. |
| `config.NPC.get(name)`, `id.NPC.get(name)` | `config.NPC[name]`, `id.NPC[name]` | A name known only at run time. An unknown name gives `null` from a `config` or `vars` table and `-1` from an `id` table (checked in the client, 2026-10-23). |
| `colour = 0xFF8800FFL`, `shape.colour` | `colour = 0xFF8800FF` | RGBA colours are `long`: the client reads them back as unsigned 32-bit numbers, above an `int`'s range. A negative `int` with the same bits works when written (checked in the client, 2026-10-23). |
| `a.plus(b)`, `a.minus(b)`, `a.times(2.0)`, `a.div(b)` on vectors | `a + b`, `a - b`, `a * 2.0`, `a / b` | Lua's own operators on the client's vectors, where the client has them (checked, 2026-10-23); the names are Kotlin's. A number on the left, `2 * a`, and `-a` raise in the client, so they are not bound. |
| `log("Ticks:", ticks)`, `formatStr(...)` | `log("Ticks:", ticks)`, `formatStr(...)` | Static imports from `Globals`. |
| A lambda or method reference for a callback | A function | Callbacks are `java.util.function` types: `Runnable`, `Consumer<T>`, `Predicate<T>`, `BooleanSupplier`, ... |
| `button.Subscribe(ui.Hook.ONBUTTONPRESSED, (self, current, previous) -> ...)` | `button:Subscribe(ui.Hook.ONBUTTONPRESSED, function(self_, current, previous) ... end)` | Each `ui.Hook` constant names the callback its hook takes, which receives the component, then the hook's values, so javac checks the lambda against the hook: `Subscribe(ui.Hook<C, ?> hook, C callback)`. A lambda parameter named `self` becomes `self_`, so that it cannot hide a method's own; unnamed parameters go as for any lambda, above. The callbacks return nothing; a Lua callback may return `false` to stop the listeners after it, which `ui.Hook.ONCLICK.stoppable()` allows: the same hook, whose callback returns a boolean, `(self, x, y) -> x > 10`, and which is the hook itself in Lua. A radial progress overlay fires `ONSELECTIONCHANGED` with two numbers, `value` and `progress`, which `ui.Hook.ONSELECTIONCHANGED_PROGRESS` types. |
| `Event.X.Subscribe(name, payload -> ...)` | `Event.X.Subscribe(name, function(payload) ... end)` | `EntityDraw` takes a `Predicate`, whose result the client acts on; every other event a `Consumer`. |
| `@Nullable` results, `Integer` and other boxes | Values that may be `nil` | Check before use, as the Lua must. |
| `World.GetGroundHeight(level, x, z)` | `local height, available = World.GetGroundHeight(level, x, z)` | A function with several return values; see [Several return values](#several-return-values). |
| `Resources.icons.herb`, `Urls.prices` | `Plugin.resources.icons.herb`, `Plugin.urls.prices` | Typed classes the build generates from `src/main/resources/` and the manifest's URLs, so a missing file or an undeclared alias does not compile. |
| `(ui.ComponentCollection) component` | `component` | Casts are not checked. |
| `(Integer) var.value`, `(String) param.defaultValue` | `var.value`, `param.defaultValue` | A value whose type varies, such as a var's, a param's or an enum's output, is `Object`; cast it to what its `valueType` says. |
| Using a member that needs a permission the build does not declare | *Never*: "Chat.AddMessage needs the chatAccess permission, which the manifest does not declare; add it to the build, oneiro { plugin { permissions { optional(chatAccess) } } }, and check CurrentPlugin.HasPermission(PluginPermissions.chatAccess) before using it." | Oneiro never adds a permission to the manifest on its own. |
| Using a member that moderation reviews closely, such as `MiniMenu.Swap(1, 2)` | `miniMenu:Swap(1, 2)`, with a warning: "MiniMenu.Swap changes the right-click menu, which Jagex's moderators review closely as input customisation: a change must not reduce the game's intended difficulty. Once the plugin meets this, @SuppressWarnings(\"oneiro:mini-menu\") on the method or class silences this warning." | The areas and their members are listed in [the plugin model](plugin-model.md#compiling). |
| Using a development-only member, such as `ExecuteConsoleCommand`, outside the dev source set | *Never*: "ExecuteConsoleCommand is for development only and must never ship; move the code that uses it into the dev source set (src/dev/java), whose Lua stays out of the zip." | |
| A class of the plugin's that extends a binding | *Never*: "NPCEntity stands for Lua that Oneiro does not write, which a class of the plugin's cannot extend." | |
| `a == b` on values the client hands out, `npc.config == config.NPC.RS3_HANS` | `a == b` | The client defines `==` for its values: two reads of the same config, entity or UI component are equal (checked in the client, 2026-10-22), though they are two objects. |
| A value the client hands out as a map key or set element, `kills.put(npc.config, 1)` | *Never*: "The client hands out a new NPCType object each time one is read, and Lua compares table keys by identity, so the map or set would not find it again; key it by a number or string the value carries, such as its id or uid." | Configs, entities and UI components are new proxies on every read, equal under `==` but not as keys (checked in the client, 2026-10-22). An API enum's constants are plain Lua values and make good keys. Every key is checked: `put`, `get`, `getOrDefault`, `containsKey`, `remove`, `add`, `contains`, and the keys of `Map.of` and `Set.of`. |
| An array passed for a Lua function's `...`, `log("values:", values)` | `log("values:", table.unpack(values))` | Spread over the values, as Java spreads it over the parameters. |
| Changing part of a value the client hands out as a copy, `instance.translation.y = 5` | *Never*: "instance.translation hands out a copy of the client's value, so changing translation.y here changes nothing in the client; change a local copy and assign it back: Vector3 translation = instance.translation; translation.y = ...; instance.translation = translation;" | The client gives copies of the vectors, tiles and margins its objects hold (checked, 2026-10-23); the overrides' `copied` lines name those types, and `not-copied` the exceptions, such as `CoordFine.position`. A read-only holder gets only the first half of the advice. |

## The plugin class and settings

| Java | Lua | Notes |
| --- | --- | --- |
| The one class that extends `Plugin` | `main.lua`, with no module table | Its fields and methods, whatever their visibility, are `local`s of `main.lua`: `this.ticks` is `ticks`. |
| `startUp()` | Its statements, after every member is defined | They run once, when the client loads the file. |
| `@Subscribe public void onServerLogic(ServerLogicEventPayload tick)` | `local function onServerLogic()`, and after the `startUp()` statements `Event.ServerLogic.Subscribe("<id>.onServerLogic", onServerLogic)` | `<id>` is the plugin's identifier. The parameter's type names the event. An event that passes nothing has an empty payload class, and its function takes no parameter. |
| `shutDown()` | The global `PluginShutdown`, which unsubscribes every `@Subscribe` method and then runs the body | The order RuneLite uses. The client requires `PluginShutdown` to be global. |
| `@Subscribe public boolean onEntityDraw(EntityDrawEventPayload event)` | A function that returns the boolean | `EntityDraw`'s callbacks return whether to draw; those of every other event return nothing. |
| A class that extends `DevPlugin`, in the dev source set | `dev/main.lua` in the development folder, whose `main.lua` requires it last | Works as the plugin class does. Its subscriptions are named `<id>.dev.<method>`, and it wraps `PluginShutdown` so that the plugin's own shutdown runs first. |
| `@ConfigGroup("herbs") interface HerbConfig` | `src/herb_config.lua`: one function per setting, over `PersistentDB` | Each setting is kept under `"<group>.<keyName>"`. The setting's name and description become its comment. |
| `@ConfigItem(keyName = "greet", name = "Greeting") default boolean greet() { return true; }` | `function HerbConfig.greet()`, which returns the stored value, or the default when nothing is stored | `boolean` uses `GetBool`, `int` and `long` `GetInt`, `double` `GetFloat`, `String` `GetString`, and an enum of the plugin's `GetString` with its constant's name. |
| `@ConfigItem(...) void setRuns(int runs);` | `function HerbConfig.setRuns(runs)`, which calls the typed setter | |
| `private HerbConfig settings;` and `settings.greet()` | `HerbConfig.greet()` | The field needs no value: every call through it calls the settings module, as RuneLite's injection makes it. |
| Two classes that extend `Plugin`, or two that extend `DevPlugin` | *Never*: "Only one class extends Plugin, because it becomes main.lua; plugin.SecondPlugin does too." | |
| A `DevPlugin` class in the main source set | *Never*: "StrayCheats extends DevPlugin, whose code must never ship; move it into the dev source set, src/dev/java." | And for a `Plugin` class in the dev source set: "Stray extends Plugin, so it becomes the main.lua that ships; keep it in src/main/java, and extend DevPlugin in the dev source set instead." |
| A constructor of the plugin class | *Never*: "The client creates the plugin class, and main.lua runs once; put start-up code in startUp() instead of a constructor." | |
| `new` of the plugin class | *Never*: "The client creates the plugin class, once, when the plugin starts; use its members directly instead of creating another." | |
| `this` passed or stored in the plugin class | *Never*: "The plugin class becomes main.lua, whose fields and methods are locals with no object to pass around; pass what is needed instead of this." | |
| Another file that uses the plugin class | *Never*: "The plugin class becomes main.lua, which no other file can require; move what this code needs from HerbPlugin into a class of its own." | |
| Calling `startUp()` | *Never*: "startUp() runs once, when the plugin starts; move what it shares into a method of its own and call that." | |
| `@Subscribe` outside the plugin class | *Never*: "Only the plugin class's methods are subscribed with @Subscribe; subscribe this one with Event.<Name>.Subscribe(name, callback), and unsubscribe it when it is done." | |
| A `@Subscribe` method without exactly one payload parameter | *Never*: "A @Subscribe method takes one parameter, the payload of the event it subscribes to, such as InventoryUpdatedEventPayload." | For a parameter of another type: "String is not an event's payload; the parameter of a @Subscribe method names the event, such as InventoryUpdatedEventPayload for InventoryUpdated." |
| A `@Subscribe` method whose return type does not fit the event | *Never*: "EntityDraw's callbacks return a boolean the client acts on; declare onDraw to return boolean." and "Logic's callbacks return nothing; declare onLogic void." | |
| Reading the payload of an event that passes nothing | *Never*: "The ServerLogic event passes nothing, so its payload has nothing to read; leave tick unused." | |
| Subscribing to an event that needs a permission or a manifest flag the build does not set | *Never*: "The ChatMessage event needs the chatAccess permission, which the manifest does not declare; add it to the build, oneiro { plugin { permissions { optional(chatAccess) } } }." and "The SettingsLayerReady event fires only when the manifest sets hasSettingsIcon; set settingsIcon = true in the build's oneiro { plugin { } } block." | |
| `@ConfigGroup` on a class | *Never*: "@ConfigGroup marks an interface of settings; make NotAnInterface an interface whose methods carry @ConfigItem." | |
| A method of a settings interface without `@ConfigItem` | *Never*: "Every method of an interface of settings is a setting's getter or setter, with @ConfigItem(keyName = ..., name = ...)." | |
| A getter that computes its default, or takes parameters | *Never*: "A setting's getter is a default method without parameters whose body only returns the default value, such as default int count() { return 5; }." | |
| A setter that is not an abstract `void` method with one parameter | *Never*: "A setting's setter is an abstract void method with one parameter, the new value, such as void setCount(int count); a getter is a default method that returns the default value." | |
| A setting of another type | *Never*: "A setting is a boolean, an int or long, a double, a String or an enum of the plugin's; PersistentDB keeps those with typed accessors." | |
| A value for a field of a settings type | *Never*: "A field of settings needs no value: every call through it calls the settings module, so remove the initializer." | Also for an assignment. |

## Calling existing Lua

Lua that Oneiro does not write is called through classes Oneiro generates from its LuaCATS annotations: the RS3 API's,
from the stubs; the libraries the plugin vendors, as [plugin-model.md](plugin-model.md#vendored-libraries) explains;
the library plugins it depends on, such as PrettyUI, from the types the build names for them, as
[plugin-model.md](plugin-model.md#library-plugins) explains; and the plugin's own Lua, from its files under
`src/main/lua/`, as [plugin-model.md](plugin-model.md#the-plugins-own-lua) explains. Three features of the
`oneiro-api` artifact describe the tables and values that pass between the plugin's code and that Lua: `@LuaName`,
records, and `@LuaReturnValues` for several return values.

| Java | Lua | Notes |
| --- | --- | --- |
| `Levels.fromPenalty(penalty)` and `Levels.PERFECT`, of the class generated from `src/main/lua/core/levels.lua` | `local Levels = require("src/core/levels")` at the top of the file, then `Levels.fromPenalty(penalty)` and `Levels.PERFECT` | The class is in the package of the file's folder, `<root>.core`, and named by the module's `---@class`, or else by its file. `---@param`, `---@field` and `---@return` give the members' types; a value without one is an `Object`. |
| `new Tally(5)` and `tally.add(2)`, of a module whose `---@class Tally` has a `new` and colon functions | `Tally.new(5)` and `tally:add(2)` | |
| `zyklib.objects.getOrElseUpdate(counts, "starts", () -> 0)`, of the vendored library's class `org.adelheid.oneiro.lib.zyklib.zyklib` | `local zyklib = require("zyklib")` at the top of the file, then `zyklib.objects.getOrElseUpdate(counts, "starts", function() return 0 end)` | A library's module is a class named as its `require` binds it, and the tables it holds are classes nested in it. |
| `prettyui.version`, of the class `org.adelheid.oneiro.lib.prettyui.prettyui` generated from a library plugin's types | `prettyui.version`, a field of the global the client gives the plugin | The class named like the alias stands for the global, and the tables it holds are classes nested in it. Its fields are final, as the global is the plugin's copy of the library's table. `prettyui.ALIAS` is the string `"prettyui"`. |
| `prettyui.isLoaded()`, of an optional dependency | `if prettyui then` in a condition, `not prettyui` negated, `wanted and prettyui` beside another condition; elsewhere `prettyui ~= nil` | |
| A use of an optional dependency's global that no check reaches | *Never*: "prettyui is an optional dependency, which the player may not have, or may turn off while the plugin runs; reach it where a check does: inside if (prettyui.isLoaded()) { ... }, or after if (!prettyui.isLoaded()) return; in the same function. Code in a lambda checks again, as it runs later." | A check reaches the `then` branch of `if (prettyui.isLoaded())` and the `else` of the negated check, the branches of `?:` alike, the right of `&&`, or of `\|\|` after a negated check, a `while` body, and what follows `if (!prettyui.isLoaded())` with a `return`, `throw`, `break` or `continue`, in the same method. It stops at a lambda, a method and a class. A value the global handed out needs no check. |
| `prettyui::isLoaded` | *Never*: "isLoaded() checks the dependency's global, which is no function of the library's; write the reference as a lambda, () -> prettyui.isLoaded(), which checks it when it is called." | |
| `@LuaName("Ingest") public void ingest(...)` | `function Tracker:Ingest(...)`; every call is `tracker:Ingest(...)` | Keeps a name that existing Lua code calls, or uses a Lua name Java cannot spell (`@LuaName("new")`). Works on methods, fields and record components. |
| A record describing a table that Lua passes in | Field reads, `position.spriteId` | `@Nullable` components for fields the Lua may leave out. |
| A class of the plugin's annotated `@LuaGlobal`, `@LuaModule` or another annotation of the generated bindings | *Never*: "@LuaModule belongs to the bindings Oneiro generates, not to the plugin's own classes; keep the Lua the plugin calls under src/main/lua, with LuaCATS annotations such as ---@param and ---@return, and call the class Oneiro generates from them." | The others are `@LuaType`, `@LuaColon`, `@LuaDot`, `@LuaIndex`, `@LuaSame`, `@LuaOperator`, `@LuaCopy`, `@LuaConstants`, `@LuaEvent`, `@LuaFromId`, `@LuaPermission`, `@LuaManifestFlag`, `@LuaReview` and `@LuaDevOnly`. |
| A `native` method | *Never*: "A native method would stand for Lua that exists already, which Oneiro binds from its annotations rather than from a declaration; keep the Lua the plugin calls under src/main/lua, with LuaCATS annotations such as ---@param and ---@return, and call the class Oneiro generates from them." | |
| A class whose Lua would be a file of the plugin's own Lua, `Tally` beside `src/main/lua/tally.lua` | *Never*: "The Lua of Tally would be src/tally.lua, a file the plugin's own Lua has already; rename the class or that file." | |
| Classes of one name from two packages, used in one file | *Never*: "In Lua, this file would require src/core/levels and src/ui/levels as one local, Levels; rename the class or file of one." | Each module is the local its class is named, in every file that uses it. |

### Several return values

A Lua function that returns several values has a record annotated `@LuaReturnValues` as its return type: in the RS3
bindings, `World.GetGroundHeight`; in the bindings of the plugin's own Lua, a function whose `---@return` tags name each
value, so that `Stats.summarise` returns a `Stats.SummariseResult`. A method of the plugin's returns several values
through a record of its own so annotated.

| Java | Lua | Notes |
| --- | --- | --- |
| `var ground = World.GetGroundHeight(0, x, z);` | `local height, available = World.GetGroundHeight(0, x, z)` | One local per component, named after it; a second call in the same scope gets names from its variable (`eastHeight`). Components that are never read are left off the end, or named `_`. |
| `ground.height()` | `height` | |
| `return new Span(low, high);` from a method of the plugin's | `return low, high` | The method returns the values; `---@return` names each. |
| `return span;` of such a local, `return span(values);` | `return low, high`, `return span(values)` | |
| Using the values anywhere but a local's initialiser | *Never*: "GroundHeight stands for several Lua values, which only a local can receive, from a call; pass its components one by one instead." | |
| Assigning such a local later, or passing it on | *Never*: "The values a Lua function returns exist only at its call; initialise stored with the call itself." and "The values a Lua function returns exist only at its call; read them through ground's components, such as ground.height()." | |
| Returning such values any other way | *Never*: "Several Lua values are returned as they are made: return new GroundHeight(...), or the call that returns them." | |

## Helpers

Each helper is a `local function` with a one-line comment, emitted once per module that needs it, after the
`require` lines.

| Helper | Used for |
| --- | --- |
| `quotient(a, b)` | Integer `/` when an operand may be negative; rounds toward zero. |
| `toInteger(d)` | `(int)` and `(long)` casts from `double`; truncates toward zero. |
| `filled(n, value)`, `grid(value, size, ...)` | `new int[n]` and other primitive arrays; arrays of more than one dimension. |
| `listContains(list, x)`, `listIndexOf(list, x)` | `List.contains`, `List.indexOf`. |
| `stringIndexOf(s, t)`, `endsWith(s, p)`, `trim(s)`, `split(text, separator, limit)`, `characters(text)` | `String.indexOf`, `endsWith`, `trim`, `split` with a plain separator, `toCharArray`. |
| `count(t)` | `size()` of a map or set. |
| `valueOr(value, default)` | `getOrDefault` and `orElse` on a value that can be `false`. |
| `computeIfAbsent`, `compute`, `computeIfPresent`, `merge` | The map methods of those names, where they give a value. |
| `builderSet(builder, text)` | A `StringBuilder`'s edits that need its whole text. |
| `listText(list)`, `setText(set)`, `mapText(map)` | A collection as Java writes it. |
| `orderedTable()`, `sortedTable(comesBefore)` | `LinkedHashMap` and `LinkedHashSet`; `TreeMap` and `TreeSet`. |
| `keysOf(t)`, `valuesOf(t)`, `addElements(set, list)`, `addKeys(set, t)`, `copyEntries(map, source)` | Copying one collection into a new one. |
| `firstKey(t)`, `lastKey(t)` | `firstKey()`, `lastKey()`, `first()` and `last()` of a sorted map or set. |
| `isInstance(value, class)` | `instanceof` a class that others extend. |
| `withMembers(object, members)` | An anonymous subclass's object. |

## Behaviour that differs

These are documented differences, not errors:

- Integers are 64-bit; 32-bit overflow is not reproduced, and `Integer.MAX_VALUE + 1` does not wrap.
- `float` is a 64-bit double, and `Math.floor` returns an integer.
- Doubles print in the client's shortest form: integral values without `.0` (`2`, not `2.0`); exponent form only
  below `0.0001` or from `1e16` up, where Java switches below `0.001` and from `1e7` (`12345678.0` prints
  `12345678`, not `1.2345678E7`); exponents in lowercase with a sign and two digits (`1e+20`, not `1.0E20`); `inf`
  and `-nan` instead of `Infinity` and `NaN`; `-0` for negative zero.
- Strings are bytes: `length`, `substring` and `indexOf` count bytes, and `toUpperCase` changes ASCII letters only.
  `formatStr` widths count characters, as Java's do.
- `null` prints as `nil`. Joining two strings with `+` becomes `a .. b`, which fails in Lua when either is `nil`, where
  Java would print `null`.
- Exceptions keep only their message; their type is lost. A caught error's message starts with the place it was raised,
  so compare it with `contains`, not `equals` (see [Errors](#errors)).
- Records are plain tables, compared by identity. Casts to classes are not checked; `List.of` is not immutable;
  modifying a collection while iterating it is not detected.
- `Integer.parseInt` and an enum's `valueOf` return `nil` on invalid input instead of throwing, and a switch whose
  selector is `nil` runs its `default` instead of throwing.
- A superclass constructor that calls a method its subclass overrides runs the superclass's version: the object
  becomes the subclass's after the superclass's `new` returns.
- Sorting is not stable.

## Decisions

- **`continue`** becomes `goto continue` with a `::continue::` label at the end of the loop body, the common Lua idiom
  and the only translation that works in every loop. The house style allows this one use of `goto`.
- **Helpers** are emitted once per module that uses them, so every file reads on its own.
- **Strings** are built with `formatStr` and `table.concat`, as described in [Building strings](#building-strings).
- **Enum constants are their own names**, which reads well in logs.
- **Nested classes and enums are defined first**, after the module table and before the outer class's members,
  because Lua must define a table before the code that uses it; a nested class used above its own definition is
  declared ahead like any other local.
- **`try`/`catch` is `pcall`.** Lua errors have no class, so one catch clause takes every error, and the try block is a
  function, so it must return on every path or on none.
- **The plugin class is `main.lua`**, a singleton whose members are locals, as in a hand-written `main.lua`.
- **Loops count from 1 when they can**, across the whole file: a variable that indexes several arrays, appears as
  `i + 1`, or is compared with another such variable still counts from 1, so most loops read as hand-written Lua
  loops do.

## Client findings

`formatStr` is documented only as "`{}` placeholders, `{0}` positional, fmtlib option syntax". Tested on client 951-1
(2026-10-21), it behaves as follows:

- **Every number arrives as a double.** `{}` prints `2` for both `2` and `2.0`, `0.30000000000000004` for
  `0.1 + 0.2`, and `9.223372036854776e+18` for `math.maxinteger`; `2^53 + 1` prints as `9007199254740992`. Integer
  options (`{:d}`, `{:x}`, `{:X}`, `{:o}`) are refused, and so are widths taken from an argument.
- **Plain or exponent notation.** `{}` prints plainly when the decimal exponent is from -4 to 15 (`0.0001`, `100000`,
  `1000000000000000`) and in exponent form otherwise (`1e-05`, `1e+16`), fmtlib's rule for its shortest format.
- **Other values:** `true` and `false` print as such, `nil` prints `nil` (also between other arguments), and tables and
  functions print their type name.
- **Supported options:** positional `{0}`, `{{` and `}}`, width with `<`, `>` and `^` (default alignment: strings
  left, numbers right), `0` padding, `+` and space signs, `f`, `.Nf`, `e`, and string precision `{:.3}`. `{:L}` adds
  no digit grouping, and `{:g}` drops trailing zeros as C does. Widths count characters, not bytes.
- **Errors do not raise.** A missing argument, an invalid option or an unclosed brace makes the client log
  `[ERROR] ... invalid format string` (or a similar message), and `formatStr` returns `nil`. Extra arguments are
  ignored. Oneiro checks every format string when it compiles, so generated code never reaches these cases.

Where the docs and the stubs leave the client's behaviour open, it was tested on client 951-1 (2026-10-22 and 23).
These findings shape the Java above:

- **No `io`, `os`, `debug` or `utf8`.** The client gives plugins Lua's `string`, `table` and `math` (with
  `math.type`), so generated code uses nothing else.
- **Copies and constants.** Vectors, tiles and margins read from the client's objects are copies; enum tables hold
  numbers; colours read back unsigned. The rows above, and the overrides' `copied`, `values` and `type` lines, follow
  from this.
