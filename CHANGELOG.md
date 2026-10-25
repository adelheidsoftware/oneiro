# Changelog

All notable changes to Oneiro are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `explainError`: copy errors from the client's console into a file, run `./gradlew explainError --input=<file>`,
  and each error of the plugin is shown with the source file, line and code of every place it names. The compiler
  writes the line maps it reads to `build/oneiro/line-maps/`. A message of the client's own, which names C++ types,
  is followed by what it means and what to do instead, such as for an argument the client requires where the docs
  let it be left out.
- Compliance warnings: a use that Jagex's moderators review closely (the chat, HTTP requests, the tracking ID,
  changes to the right-click menu, finding other players) compiles with a warning that names what they look at.
  `@SuppressWarnings("oneiro:<area>")` silences an area's warnings once the plugin meets them.
- [From RuneLite to RS3](docs/from-runelite.md), a guide for RuneLite developers: what a RuneLite plugin's events,
  getters, settings, overlays, notifiers and timers become, and how the rules differ between the games.
- Plugins written in Kotlin: apply the Kotlin JVM plugin beside Oneiro's and put the sources in `src/main/kotlin/`.
  `oneiroKotlin` compiles them to Lua with Oneiro's Kotlin front end, in a worker process, and reports a rejection as
  kotlinc reports an error. [kotlin-subset.md](docs/kotlin-subset.md) lists the Kotlin it accepts: everything the
  conformance suite covers for Java, which every case now checks from Kotlin too, and Kotlin's own forms, such as a
  chain of collection functions as one loop, `require` and `check`, fun interfaces and object expressions, and maps
  and sets that keep the order Kotlin promises. The RS3 bindings generated for such a plugin have a `Subscribe` for
  each callback a hook takes, so a hook's callback is a trailing lambda, `label.Subscribe(ui.Hook.ONCLICK) { ... }`.
  It runs on the Kotlin Analysis API, which JetBrains publishes only to its own repositories: the Gradle plugin adds
  them to a build that declares its repositories in `build.gradle.kts`, for those libraries alone, and
  [plugin-model.md](docs/plugin-model.md#where-the-kotlin-front-ends-libraries-come-from) shows what to add to
  `settings.gradle.kts` where a build declares them there.
- Kotlin's own constructs: extension functions, which take their receiver first as `self`; the scope functions `let`,
  `also`, `apply`, `run` and `with`, whose code runs where they are called, so that
  `ui.Text(parent).apply { SetPos(10, 20) }` configures the new component in place, and on `receiver?.`, as a
  statement or, for `apply` and `also`, as a local's value, their code runs when the receiver is not nil; in both,
  the receiver's members named without it, the Kotlin library's included, as in `fun List<Int>.total() = sum()`; an
  `if` or `when` with blocks inside an expression, whose value a local before the statement takes; chains over a map or its entries, such as
  `counts.maxByOrNull { it.value }?.key`, and loops that read `entry.key` and `entry.value`; the collection functions
  `distinct`, `take`, `drop`, `takeWhile`, `dropWhile`, `flatMap`, `groupBy` and `associate`, and the string functions
  `padStart`, `padEnd`, `removePrefix`, `removeSuffix`, `substringBefore`, `substringAfter`, `isBlank`, `trimStart`
  and `trimEnd`; `requireNotNull` and `checkNotNull`; and the casts `as` and `as?`. An expression body can be anything
  a `return` can, and a class's property can be built with `apply`.
- `LuaString`, Lua's string patterns where Java and Kotlin have regular expressions: `match`, `captures`, `find`,
  `gsub` and `gmatch`, each Lua's string function of its name, with the pattern syntax in its Javadoc. The errors for
  regular expressions named it before it existed.

### Changed

- Threads, executors, futures and timers, and reading the current time, fail with an error that says what to write
  instead, counting the ticks of `Event.ServerLogic` or `Event.Logic`, rather than as not supported yet: the client
  runs plugins on one thread and gives them no clock.
- Every Lua file starts with its path inside the plugin, `-- src/core/row.lua`, before its description. The client
  names a file in its errors only by the file's first line, so its errors now say which file they come from.
- The RS3 bindings declare the values a function takes any number of, such as `log`'s, as nullable, as Lua's `...`
  takes nil.
- `Permission`, the type of the build's `permissions { }` block, moved from `org.adelheid.oneiro.gradle` to
  `org.adelheid.oneiro.plugin`. Build scripts that write `optional(chatAccess)` need no change; one that imports the
  type imports it from its new package.
- The RS3 bindings follow what the client has, where a check on the beta world found it differs from the docs. They
  no longer have `new ui.ModelCamera()`, `ui.ModelGroup.AddCamera()` without a camera, `ui.Model.rotation`, `ui.Text`'s
  `lineSpacing`, `shadowColour` and `shadowRGBA`, or `ui.Sprite.AlignTo`, which compiled and then failed or read
  `nil`. They gain the members the client has and the docs leave out: `aabb` on `LocEntity`, `paramCount` on NPC, loc
  and object types, `isValid` on object types, `valid` on coordinates, `ShapeEntity.drawDistance`, and the menu of
  `MiniMenuEntryAdded`'s payload; `NPCType.attackOpPriority` is now `attackOpHasPriority`, the client's name. The
  Javadoc of a member whose behaviour in the client differs from its documentation says how, in a paragraph that
  begins "In the client:", as for `SetPos`'s self percentages.

### Fixed

- A local or parameter named like a private method or static field of the file, or like a member of the plugin class,
  no longer hides that member in the Lua when the same method uses it. `this.shown = shown` in the plugin class
  compiled to `shown = shown`, which changed nothing, and a private method `step` that declared a local `step` called
  the local. Such a local now gets a trailing underscore, `shown_`.
- A method reference to a private method declared further down the file, such as `this::label`, no longer reaches
  `nil`: the method's local is declared ahead, as it is for a call. A parameter of the method named like the method
  itself no longer hides it in the function the reference becomes.
- A record with methods stores each component under the name its accessor reads. A component named like a Lua global,
  such as `type`, was stored as `self.type_` and read back as `nil`.
- A reference to `list.add` or `set.add` whose value the code reads, as `stream.filter(seen::add)` does, is now an
  error that says to write a lambda. Its Lua function, an assignment, gave no value, so the filter kept nothing. Where
  nothing reads the value, a stream's `forEach(found::add)` is now opened in place, `found[#found + 1] = item`, and a
  stream's `forEach(String::length)`, whose value would go nowhere, is an error instead of a failure inside the
  compiler.

## [0.1.0] - 2026-10-24

The first release.

### Added

- The Java front end: a plugin written in Java 25 compiles to Lua 5.4 for the RS3 Client Plugin API inside its own
  `compileJava`. It supports classes, interfaces, records, enums, nested and anonymous classes, generics, lambdas and
  method references, switches and patterns, `try`/`catch`, collections, streams, strings and `String.format`.
  [docs/java-subset.md](docs/java-subset.md) lists every construct and the Lua it becomes.
- The plugin model: the class that extends `Plugin` becomes `main.lua`, `@Subscribe` methods subscribe to client
  events, and `@ConfigGroup` interfaces become settings kept in `PersistentDB`.
- RS3 bindings generated on the developer's machine from runescape-api-stubs, from a local checkout or fetched from
  GitHub at a pinned or the latest commit.
- The Gradle plugin `org.adelheid.oneiro`: `plugin.json` from the build, typed `Resources` and `Urls` classes, checks
  of the folder that ships, a reproducible zip, a dev source set for helpers that never ship, `linkDevPlugin` for the
  client's `dev_plugins` folder, and Lua tests on the bundled Lua 5.4.8.
- Compile-time checks against the manifest: code that needs a permission or an icon does not compile unless the build
  declares it, and development-only members such as `ExecuteConsoleCommand` compile only in the dev source set.

[Unreleased]: https://github.com/adelheidsoftware/oneiro/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/adelheidsoftware/oneiro/releases/tag/v0.1.0
