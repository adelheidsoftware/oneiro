# Changelog

All notable changes to Oneiro are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Kotlin code of the dev source set can call the main set's top-level functions and properties,
  `Levels.clampLevel(it)`, which before was not supported.
- A sort with more steps after it in a Kotlin chain, `drops.sortedByDescending { it.price }.take(2)`: the sorted
  list is a local of its own, `sorted`, which a second loop walks for the steps after it.
- An annotation on a Kotlin expression, `val names = @Suppress("UNCHECKED_CAST") (value as List<String>)`,
  which the Lua leaves out.
- A Kotlin value that Lua reads twice and that a second read could change, such as a call's, is now read once into
  a local before the statement, named after the property or the call that gives it: `nearest(entries)?.length` is
  `local nearest = Hoisting.nearest(entries)` and `nearest and #nearest or 0`. So are `detailed(entries).size in 1..9`,
  `setting(name) as? String`, `text[middle(text) + 1]`, `labeller(tag)::label`, and `addAll(ledger.detailed())` in a
  `buildList`. Before, each was refused, with "store it in a local first".
- More Kotlin values that Lua makes with statements now run before the statement they are in, as a chain or an `if`
  with blocks already did: a `buildString { ... }`, whose pieces are joined in place, `log(table.concat(text))`; a
  scope function whose lambda is more than one value, or whose receiver is a literal, `1 + "herbs".let { it.length }`;
  and a `joinToString` with a prefix, a postfix or a transform.
- An `if` whose branch needs statements, such as `if (changes.isEmpty()) "nothing" else changes.joinToString { ... }`,
  is now statements of its own, with the branch's value returned or assigned in it.
- A lambda of several statements in a Kotlin chain, `mapIndexed { index, drop -> val number = index + 1; ... }`: its
  statements run in the loop's body before its value. A value in a chain's lambda that needs statements of its own,
  such as another chain, runs them there too.
- Java values that Lua makes with statements now run before the statement they are in, into a local, as in Kotlin: a
  switch or a conditional inside an expression, `log("The monster is " + switch (mood) { ... })`, and a stream,
  `log((int) names.stream().filter(...).count() + " empty")`. A stream on the right of `&&` or `||` runs inside an
  `if` that only Java's right side would reach, `local both = wanted ~= ""` and `if both then ... end`. Before, each
  was refused.
- A Java value that Lua reads twice and that a second read could change, such as a call's, is now read once into a
  local before the statement, as in Kotlin: `text.charAt(middle(text) + 1)`, `counts[middle(text)] += 1`,
  `setting(name) instanceof String text`, `labeller(tag)::label`, `lines.addAll(detailed(entries))`, and a right side
  of `&` with a call, `ready & isLong(name)`. Before, each was refused, with "store it in a local first".
- `limit(n)` and `skip(n)` in a Java stream, which count in the loop, `local taken = 0` with
  `if taken == n then break end`, and a `sorted` with more steps after it, `sorted(byPrice).limit(2)`, whose sorted
  list is a local of its own, which a second loop walks for the steps after it.
- A lambda of several statements in a Java stream, `filter(drop -> { int worth = ...; return worth > 150; })`: its
  statements run in the loop's body before its value, which it returns at its end. A value in a stream's lambda that
  needs statements of its own, such as another stream, runs them there too.

### Changed

- The helper that counts a map's or a set's entries, for `map.size`, is now `tableLength`, the name Lua developers
  know it by, rather than `count`. A local of your plugin's named `count` keeps its name now, where before it became
  `count_`.
- When a Kotlin value cannot run before its statement, the message now says why, such as "but it comes after
  mood.toString(), which would then run later" or "but it runs only when the if takes its branch".
- A Kotlin `if` or `when` inside an expression, whose value a local before the statement takes, names that local after
  the value's type where no parameter names it, `text` for a string, rather than `value`.
- `val repeated = buildString { ... }`, and a `run { ... }` without locals of its own, now declare the local where
  its value is given, `local repeated = table.concat(text)`, rather than declaring it empty first.
- The helpers Oneiro's Lua uses, such as `orderedTable`, are files of their own under `utils/`,
  `utils/ordered_table.lua`, which each module that uses one requires, `local orderedTable = require("utils/ordered_table")`,
  rather than a copy defined in each module. The plugin carries each helper once, and only those its code uses; those
  only the dev source set uses go into the development folder alone. `checkPlugin` accepts them, a vendored library may
  not ship files in `utils/`, and `explainError` names a line of one as the helper it is.
- A list, a set or a map that a local Oneiro makes up in Kotlin holds is named after the parameter of the plugin's or
  the client's function it is handed to, or after the property it becomes, `texts` or `lines`, where that name is
  free, rather than `list`. A list a chain reads from a call before its loop is named after the call, `top`.
- A Kotlin map or set whose order nothing can see is a plain table, `{}` or `{ guam = 10 }`, rather than one the helper
  `orderedTable()` makes: the value of a `val`, a local or a private property, that the code only reads, sets, tests
  and removes keys of, counts and tests for emptiness.
- A Java stream that sorts a list with nothing before the sort, `harvests.stream().sorted(...).toList()`, copies the
  list with `table.move(harvests, 1, #harvests, 1, {})` before sorting it, as Kotlin's `sortedBy` does, rather than in
  a loop.
- A list or a set a Java stream builds is named after the parameter of the plugin's or the client's method it is
  handed to, or after the field it becomes, `texts` or `lines`, where that name is free, rather than `list`, as in
  Kotlin. A field assigned a stream's list takes the finished list, `self.lines = lines`.
- A stream kept or passed on as a Java stream, `return names.stream();`, now says what to do: "end the stream where it
  starts, with a step such as toList() or count(), instead of keeping or passing the stream itself."

### Fixed

- Each name Oneiro makes up for a local in Kotlin is now taken once in a function, numbered from the name itself.
  Before, two locals of one kind could be declared with one name in a block, `local list` and then `local list`, and
  numbers could follow numbers, `count22` beside a local `count` of the source.
- Two values in one Kotlin statement that each run before it, such as two chains or two `buildString`s, now both
  compile. Before, the second was refused, as though the first's calls came before it in the statement; and in a
  string joined with `+`, the values ran before the statement from the last to the first.
- A call whose table argument is too wide for a line of its own now opens the table on the call's line,
  `shown:show(Section.SESSION, {`, its entries indented once, as StyLua lays such a call out. Before, each argument
  went on a line of its own, which StyLua would reformat.
- `./gradlew build` now refreshes the development folder, `build/oneiro/dev-plugin/`, of a plugin with a dev source
  set, so that after `linkDevPlugin` each build updates what the client loads, as the docs said. Before, only
  `assembleDevPlugin` wrote it.
- An `else` with nothing in it, such as Kotlin's `else -> {}` or Java's `else {}`, now leaves no `else` in the Lua.
  One that holds a comment keeps it.
- A literal where Lua reads a value twice, as in Kotlin's `5 in 1..9`, now compiles, `5 >= 1 and 5 <= 9`. Before,
  it was refused, as a call would be, which might give a different value the second time.
- A Kotlin data class or pair compared with `null`, `if (saved != null)`, now compiles to `saved ~= nil`. Before, it
  was refused as though it compared two objects by `equals`; and `==` with a data class on its right side only, such
  as `value == tile`, was taken for identity, where it is now refused as with one on its left.
- Each name Oneiro makes up for a local in Java is now taken once in a function, as in Kotlin, and none takes the
  name of a global the module reads: a switch's selector named after `Kinds.log()` beside a call of `log` is `log2`.
  Before, the selector became `local log`, and the `log(...)` after it called the text.
- A local Oneiro makes up in Kotlin, such as a chain's `sum` or `list`, no longer hides a name the code around it
  reads. Before, `values.sumOf { it * 2 } + sum(1, 2)` beside a private function `sum` became
  `return sum + sum(1, 2)`, `values.count { it < keys.size }` called the count it was making, and a chain inside a
  `forEach` lambda could hide a parameter of the function around it. The local now takes a number, `sum2`.
- A chain of collection functions whose value is an argument, `log(drops.filter { ... }.map { it.item }.joinToString())`,
  now runs once. Before, the Lua ran the chain's first part a second time, in a loop of its own whose list nothing
  read, so each lambda in it ran twice for each element.
- A comment above a Kotlin `when` branch that has no code, `// Other states change nothing` above `else -> {}`, now
  stays in the Lua, as that branch's content. Before, it was dropped without the warning a comment Oneiro cannot
  place gets.
- Code of the dev source set now sees the main set's classes as the main set's Lua makes them: Oneiro reads the main
  set's sources with it, where before it saw only their compiled classes, and got the Lua wrong without an error. A
  Kotlin enum's `entries` were empty, `#{}`; an enum whose constants are objects was taken for one whose constants are
  strings, so its `ordinal` was refused in Kotlin and its `name()` in Java gave the constant's table; data classes,
  records and inner classes were built wrongly; and a type test did not see the main set's subclasses. Dev code that
  would change what the main set's Lua makes of a class is now an error, such as reading the order of an enum whose
  constants the main set's Lua makes strings.
- A Java class without members of its objects, `public class Marker {}`, now has objects where code creates one or
  another class extends it. Before, its Lua was a table without `new`, which failed when the plugin ran.
- Calling an object as a function in Kotlin, `doubler(value)` or `Doubler()(value)`, where its class implements a
  function type or has an `operator fun invoke`, now calls that object's `invoke`: `doubler:invoke(value)`. Before,
  in a class's function, the Lua called the function's own object, `self:invoke(value)`, and in an object's, it was an
  error.
- Calling a property of another object as a function in Kotlin, `machine.callback(value)`, now calls the property's
  value. Before, the Lua called the object that holds it, `machine(value)`.

## [0.3.0] - 2026-10-27

### Added

- **Write part of your plugin in Lua.** Lua files in `src/main/lua/` ship unchanged in the plugin's `src/` folder:
  `src/main/lua/core/levels.lua` becomes `src/core/levels.lua`. Oneiro reads their LuaCATS annotations (`---@param`,
  `---@return`, ...) and generates classes for them, so Java and Kotlin code can call them directly. Lua in
  `src/dev/lua/` works the same way, but only goes into the development folder. `testLua` and `explainError` cover
  these files too. See [The plugin's own Lua](docs/plugin-model.md#the-plugins-own-lua).
- **Copy a Lua library into your plugin** ("vendoring"), such as Zyklib:

  ```kotlin
  oneiro {
      libraries {
          vendored("zyklib") {
              remote("https://codeberg.org/ZyklopMarco/runescape-api-zyklib", "<commit>")
              from("src")
          }
      }
  }
  ```

  The library's Lua files and its licence ship inside your plugin, and Java and Kotlin call it through generated
  classes in `org.adelheid.oneiro.lib.zyklib`. A library without a licence fails the build. Use
  `development("name") { ... }` for a library that only your development helpers use. See
  [Vendored libraries](docs/plugin-model.md#vendored-libraries).
- **Call a library plugin from Java or Kotlin**, such as PrettyUI. A library plugin is installed by the player and
  never ships with your plugin. Point the build at LuaCATS type definitions for it, and Oneiro generates a class for
  it:

  ```kotlin
  dependencies {
      required("prettyui", 6398181) {
          types { local(file("lua-types/prettyui")) }
      }
  }
  ```

  For an `optional(...)` dependency, check `prettyui.isLoaded()` before using it: the player may not have the library,
  or may turn it off while your plugin runs, so code that uses it without a check does not compile. See
  [Library plugins](docs/plugin-model.md#library-plugins).
- **Develop against a library plugin that is not published yet**, or against a local copy of one:
  `devFolder("rsprite")` makes the development folder load it from a folder on your machine instead of by its Mod.io
  ID. `development("probe", 5151)` adds a dependency that only your development helpers use.
- **More checks before you ship.** `checkPlugin` now also fails when:
  - a Lua file in `src/` does not start with its own path as a comment, `-- src/core/levels.lua` (the client's error
    messages name a file by that line);
  - a `require('...')` in single quotes names a file the plugin does not have (double quotes were already checked);
  - any Lua file, a library's included, uses a development-only function such as `ExecuteConsoleCommand`;
  - Lua outside a vendored library uses something that needs a permission the manifest does not request;
  - no Lua requires a vendored library;
  - a dependency has a `devFolder` but no Mod.io ID.
- **`explainError` mentions library versions.** When the client says a name is nil, `explainError` now adds that the
  player may have a different version of a library plugin than the one your types describe.
- **Stubs from Codeberg and GitLab.** `stubs { remote(...) }` now takes Codeberg and GitLab repositories as well as
  GitHub ones, and says clearly when it cannot find a repository or commit.
- **A guide to using Lua.** [Using Lua](docs/using-lua.md) explains the three kinds of Lua a plugin can use, where
  their types come from, how to write a definition file for a library that has none, how to check it and share it,
  and the overrides file.
- **More of Kotlin compiles.**
  - A chain of collection functions anywhere a value goes, such as `if (drops.any { it.rare })` or
    `drops.count { ... } + 1`: its loop runs before the statement, where nothing the statement does would see the
    difference. So does a chain, an `if` or a `when` on the right of `&&` and `||`, inside an `if` that runs it only
    where Kotlin's right side would run.
  - `fold`, `reduce`, `average`, `single`, `flatten`, `filterIsInstance`, `chunked`, `windowed` and `zip` in a
    chain, and a text's `count { }`, `any { }`, `all { }`, `none { }`, `first { }`, `last { }`, `find { }` and
    `forEach { }`, which walk its chars.
  - `repeat(n) { }`, `takeIf { }` and `takeUnless { }`, `run { }` that gives a value, and `buildList { }`,
    `buildSet { }` and `buildMap { }`.
  - On numbers, `coerceIn`, `coerceAtLeast`, `coerceAtMost`, `roundToInt` and `compareTo`; on text, `replace`,
    `lines()`, `compareTo`, `orEmpty()`, `isNullOrEmpty()` and `isNullOrBlank()`.
  - On collections, a map's `keys` and `values` as values of their own, `getOrElse`, `clear()`, `reversed()`,
    `orEmpty()` and `isNullOrEmpty()`, and `union`, `intersect` and `subtract` of two sets.
  - A file of top-level functions and properties, as a module named after the file, `Levels.kt` as
    `src/levels.lua`, which other files require. Beside a class, private top-level functions and properties are
    locals of the class's module.
  - Secondary constructors: one that passes its parameters on to the primary constructor gives the rest their
    defaults, and a class without a primary constructor takes its one secondary constructor's parameters.
    Extension properties, whose getters are functions that take the receiver first.
  - `Pair`, `Triple` and `a to b`, as tables of their parts; `val (a, b) = ...` of a pair, a data class's value, a
    list, or `partition`'s two lists; `for ((name, level) in entries)`; `withIndex()`, `forEachIndexed`,
    `mapIndexed` and `filterIndexed`, whose positions count from 0 as Kotlin's do; and `toMap()`.
- **Module descriptors.** Oneiro's Java modules now have a `module-info`, named after their packages, such as
  `org.adelheid.oneiro.api`, for builds that use the module path.

### Changed

- **The RS3 API's types come from Oneiro's own stubs**, [rs3-api-types](https://github.com/adelheidsoftware/rs3-api-types),
  which are generated from the documentation and corrected where the beta client differs. Point the `stubs { }` block
  at them:

  ```kotlin
  oneiro {
      stubs {
          remote("https://github.com/adelheidsoftware/rs3-api-types", "871c2d2818e84eaf55163a0099ad89b444cb5b40")
      }
  }
  ```

  or `local(file(...))` at a checkout of them. The RS3 bindings change with them:
  - a base URL, `Urls.<alias>`, is the client's `PluginURL` object, not a `String`; `new HTTPRequest` takes it as before;
  - `game.options.ClientOption` is now `ClientOption`, `ComboEntry` and `ListEntry` are `ui.ComboEntry` and
    `ui.ListEntry`, and `vars.VarClanSettings` is `vars.VarClanSetting`, the client's name;
  - `PluginResourceLoadResult`'s constants are the client's: `SUCCESS`, `ERROR_FILESYSTEM`, `ERROR_DECODE`,
    `ERROR_EXCEEDS_LIMITS` and `ERROR_CONSTRUCTION`;
  - `ui.GroupBox.headerText` is a `ui.TextData`, as in the client;
  - an optional parameter no longer takes `null`: call the shorter form of the method, which leaves it out;
  - the fields of the filter settings (`NPCListFilterSettings` and the others) and of the shape drawing settings are
    `Integer`, `Boolean` and the like, and `null` until set, as each key may be left out.
- **No more hand-written declarations of Lua.** Your own classes can no longer describe existing Lua with the
  bindings' annotations (`@LuaModule`, `@LuaGlobal`, ...), Java `native` methods or Kotlin `external` functions. Put
  that Lua in `src/main/lua/` with LuaCATS annotations instead, and Oneiro generates the classes.
- **The bindings' annotations moved** to `org.adelheid.oneiro.api.binding`. `org.adelheid.oneiro.api` now holds only
  what your own code uses: `Plugin`, `DevPlugin`, `Subscribe`, `ConfigGroup`, `ConfigItem`, `LuaName`,
  `LuaReturnValues` and `LuaString`.
- **The Gradle plugin's task classes moved** to `org.adelheid.oneiro.gradle.tasks`, such as
  `org.adelheid.oneiro.gradle.tasks.CheckPlugin`. Only a build that names a task by its type, as in
  `tasks.withType<CheckPlugin>()`, needs the new import; the tasks' names are unchanged.
- **Dependencies named by a folder.** `required(alias, "folder")` and `optional(alias, "folder")` are removed; write
  `required(alias) { devFolder("folder") }` instead. The build properties `getRequiredDependencies()` and
  `getOptionalDependencies()` are removed too.
- **Dependency aliases.** An alias can no longer be a name that Lua or the client already uses as a global, such as
  `print` or `Player`.
- **`linkDevPlugin`** now links the development folder whenever its manifest differs from the shipped one, because
  of a `devFolder` or a `development(...)` dependency, and no longer only when the plugin has a dev source set.
- **Download cache moved.** Downloaded repositories are now kept in `caches/oneiro/repositories/` in Gradle's user
  home, instead of `caches/oneiro/stubs/`. The stubs are downloaded once more, and the old folder can be deleted.
- **A lambda for an interface that returns `Void`**, such as `Function<T, Void>`, no longer ends in `return nil` in
  its Lua: the `return null;` Java needs, or the `null` a Kotlin lambda ends with, is what a Lua function does by
  ending.

### Fixed

- A top-level property in a Kotlin file with an object or a class, other than the plugin class, is now a local of
  that file's module when it is private, and an error otherwise. Before, the Lua read it as a global that was never
  set.
- Two classes with the same name from different packages, used in the same file, now give an error. Before, Oneiro
  required the first one for both.
- A class of fields that a function takes as one type of a union, as in `string|Options`, is now a table your plugin
  can build, with a public constructor, as it already was for a parameter of that type alone.
- Kotlin can now use a library's generic classes and functions where `nil` is a type argument, such as an event
  whose listeners return nothing. Type variables now take any value, and `nil` is `@Nullable Void`; before, Kotlin saw
  a `Void` that could not be null, which no lambda could return.
- The Javadoc of a class that only holds the fields its subclasses share now says so, rather than that the library
  hands out its values.
- Two modules of your own Lua with one name in different folders, such as `core/settings.lua` and
  `ui/settings.lua`, are now two classes, `<root>.core.Settings` and `<root>.ui.Settings`; before, their bindings did
  not compile. A dotted `---@class` name now nests in the class its first part names, such as `core.Levels.Options`
  in the module `core.Levels`, and is otherwise a class of its file's package.
- Names Java cannot give the classes of your own Lua now fail `oneiroLuaBindings` with the file each comes from,
  instead of a compile error in the generated bindings: a folder no package can be named as (`ui-parts`), a class
  with the name of a package that a folder or your code makes (`---@class core` beside the folder `core/`), and a
  class both `src/main/lua/` and `src/dev/lua/` have. See
  [The plugin's own Lua](docs/plugin-model.md#the-plugins-own-lua).

## [0.2.0] - 2026-10-25

### Added

- **Write plugins in Kotlin.** Apply the Kotlin JVM plugin next to Oneiro's and put your sources in
  `src/main/kotlin/`. The new `oneiroKotlin` task compiles them to Lua, and reports problems the way the Kotlin
  compiler reports errors. Everything Oneiro supports in Java works in Kotlin too, along with Kotlin's own features
  (next entry). A hook's callback can be a trailing lambda: `label.Subscribe(ui.Hook.ONCLICK) { ... }`. See
  [kotlin-subset.md](docs/kotlin-subset.md) for everything it accepts.

  Oneiro's Kotlin support needs libraries that JetBrains publishes only in its own repositories. The Gradle plugin
  adds those repositories for you when your build declares its repositories in `build.gradle.kts`; when it declares
  them in `settings.gradle.kts`, [add them there](docs/plugin-model.md#where-the-kotlin-front-ends-libraries-come-from).
- **Kotlin's own features**, among them:
  - extension functions;
  - the scope functions `let`, `also`, `apply`, `run` and `with`, also after `?.`, so that
    `ui.Text(parent).apply { SetPos(10, 20) }` sets up a new component in place;
  - `if` and `when` blocks used as values, and function bodies written as `= expression`, whatever the expression;
  - chains of collection functions, which become one loop, over maps too: `counts.maxByOrNull { it.value }?.key`;
  - the collection functions `distinct`, `take`, `drop`, `takeWhile`, `dropWhile`, `flatMap`, `groupBy` and
    `associate`;
  - the string functions `padStart`, `padEnd`, `removePrefix`, `removeSuffix`, `substringBefore`, `substringAfter`,
    `isBlank`, `trimStart` and `trimEnd`;
  - `require`, `check`, `requireNotNull`, `checkNotNull`, `as` and `as?`;
  - fun interfaces and object expressions;
  - maps and sets that keep the order items were added in, as Kotlin's do.
- **`explainError` turns client errors into source lines.** Copy errors from the client's console into a file and run
  `./gradlew explainError --input=<file>`: each error is shown with the file, line and code of your Java or Kotlin
  source that it came from. For some of the client's own messages, which name C++ types, it also explains what went
  wrong and what to do instead.
- **Warnings for what moderators look at.** Code that uses something Jagex's moderators review closely (the chat, HTTP
  requests, the tracking ID, changes to the right-click menu, finding other players) compiles with a warning that says
  what they check. Once your plugin meets it, silence that area's warnings with `@SuppressWarnings("oneiro:<area>")`,
  or `@Suppress("oneiro:<area>")` in Kotlin.
- **A guide for RuneLite developers**, [From RuneLite to RS3](docs/from-runelite.md): what RuneLite's events,
  getters, settings, overlays, notifiers and timers become, and how the two games' rules differ.
- **`LuaString`, for Lua's string patterns.** Lua has its own patterns instead of regular expressions.
  `LuaString.match`, `captures`, `find`, `gsub` and `gmatch` call Lua's string functions of the same names, and their
  Javadoc explains the pattern syntax. The errors for regular expressions already suggested `LuaString`; now it
  exists.

### Changed

- Stubs updated.
- **Threads, timers and the clock** now give an error that says what to do instead: count the ticks of
  `Event.ServerLogic` or `Event.Logic`. They used to be reported as not supported yet, but the client runs plugins on
  one thread and gives them no clock, so they will never work.
- **Every generated Lua file starts with its path**, `-- src/core/row.lua`. The client names a file in its errors only
  by the file's first line, so its errors now say which file they come from.
- **Functions that take any number of values**, such as `log`, now accept `null` among them, as Lua accepts `nil`.
- **`Permission` moved** from `org.adelheid.oneiro.gradle` to `org.adelheid.oneiro.plugin`. Build scripts that write
  `optional(chatAccess)` need no change; one that imports the type imports it from the new package.
- **The RS3 bindings match the real client** where testing on the beta world found that it differs from the docs:
  - removed, because they compiled and then failed or read `nil`: `new ui.ModelCamera()`, `ui.ModelGroup.AddCamera()`
    without a camera, `ui.Model.rotation`, `ui.Text`'s `lineSpacing`, `shadowColour` and `shadowRGBA`, and
    `ui.Sprite.AlignTo`;
  - added, because the client has them although the docs leave them out: `aabb` on `LocEntity`, `paramCount` on NPC,
    loc and object types, `isValid` on object types, `valid` on coordinates, `ShapeEntity.drawDistance`, and the menu
    on `MiniMenuEntryAdded`'s payload;
  - renamed to the client's name: `NPCType.attackOpPriority` is now `attackOpHasPriority`;
  - where a member behaves differently in the client than documented, its Javadoc says how, in a paragraph that
    starts "In the client:", as on `SetPos`.

### Fixed

- **A local or parameter could hide a member of the same name.** When a method used both a local and a private
  method, a static field or a member of the plugin class with the same name, the Lua mixed them up: `this.shown =
  shown` in the plugin class compiled to `shown = shown`, which did nothing, and a private method `step` that declared
  a local `step` called the local. Such a local now gets a trailing underscore, `shown_`.
- **A method reference to a private method defined further down the file**, such as `this::label`, was `nil`. It now
  works, as a call already did, and a parameter named like the method no longer hides it.
- **A record component named like a Lua global**, such as `type`, in a record with methods, was stored as
  `self.type_` and read back as `nil`. Each component is now stored under the name its accessor reads.
- **`stream.filter(seen::add)` kept nothing**, because the Lua for `add` gives no value. Using the value of a
  `list.add` or `set.add` reference is now an error that says to write a lambda. Where nothing uses the value, as in
  `forEach(found::add)`, the Lua is written in place, `found[#found + 1] = item`; `forEach(String::length)`, whose
  value goes nowhere, is now an error instead of crashing the compiler.

## [0.1.0] - 2026-10-24

The first release.

### Added

- **The Java compiler.** A plugin written in Java 25 compiles to Lua 5.4 for the RS3 Client Plugin API, as part of the
  normal `compileJava`. It supports classes, interfaces, records, enums, nested and anonymous classes, generics,
  lambdas and method references, switches and patterns, `try`/`catch`, collections, streams, strings and
  `String.format`. [docs/java-subset.md](docs/java-subset.md) lists every construct and the Lua it becomes.
- **The plugin model.** The class that extends `Plugin` becomes `main.lua`, `@Subscribe` methods subscribe to client
  events, and `@ConfigGroup` interfaces become settings saved in `PersistentDB`.
- **RS3 bindings**, Java classes for the client's API, generated on your machine from the RS3 API stubs: a local
  checkout, or a download from GitHub at a fixed or the latest commit.
- **The Gradle plugin `org.adelheid.oneiro`**, which:
  - writes `plugin.json` from the build;
  - generates typed `Resources` and `Urls` classes for your files and base URLs;
  - checks the folder that ships, and zips it the same way every time;
  - adds a dev source set for helpers that never ship;
  - links the plugin into the client's `dev_plugins` folder with `linkDevPlugin`;
  - runs Lua tests on a bundled Lua 5.4.8.
- **Checks against the manifest when compiling.** Code that needs a permission or an icon compiles only if the build
  declares it, and development-only functions such as `ExecuteConsoleCommand` compile only in the dev source set.

[Unreleased]: https://github.com/adelheidsoftware/oneiro/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/adelheidsoftware/oneiro/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/adelheidsoftware/oneiro/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/adelheidsoftware/oneiro/releases/tag/v0.1.0
