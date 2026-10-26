# Changelog

All notable changes to Oneiro are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

### Changed

- **The RS3 API's types come from Oneiro's own stubs**, [rs3-api-types](https://github.com/adelheidsoftware/rs3-api-types),
  which are generated from the documentation and corrected where the beta client differs. Point the `stubs { }` block
  at them:

  ```kotlin
  oneiro {
      stubs {
          remote("https://github.com/adelheidsoftware/rs3-api-types", "45044c99dd347a46629aaa883e3e19f21386ad67")
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
- **Dependencies named by a folder.** `required(alias, "folder")` and `optional(alias, "folder")` are removed; write
  `required(alias) { devFolder("folder") }` instead. The build properties `getRequiredDependencies()` and
  `getOptionalDependencies()` are removed too.
- **Dependency aliases.** An alias can no longer be a name that Lua or the client already uses as a global, such as
  `print` or `Player`.
- **`linkDevPlugin`** now links the development folder whenever its manifest differs from the shipped one, because
  of a `devFolder` or a `development(...)` dependency, and no longer only when the plugin has a dev source set.
- **Download cache moved.** Downloaded repositories are now kept in `caches/oneiro/repositories/` in Gradle's user
  home, instead of `caches/oneiro/stubs/`. The stubs are downloaded once more, and the old folder can be deleted.

### Fixed

- Two classes with the same name from different packages, used in the same file, now give an error. Before, Oneiro
  required the first one for both.

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

[Unreleased]: https://github.com/adelheidsoftware/oneiro/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/adelheidsoftware/oneiro/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/adelheidsoftware/oneiro/releases/tag/v0.1.0
