# The plugin model and the build

How a plugin written in Java or Kotlin becomes an RS3 plugin: the project's layout, the `oneiro { }` block that
configures it, the tasks that turn it into the folder the client loads and the zip that goes to Mod.io, and the checks
on the way. What each construct becomes is in [java-subset.md](java-subset.md) for Java and
[kotlin-subset.md](kotlin-subset.md) for Kotlin. Using Lua that Oneiro does not write, and writing the types of a
library that has none, is in [using-lua.md](using-lua.md).

## A plugin project

```text
build.gradle.kts           plugins { id("org.adelheid.oneiro") }, the version, and the oneiro { } block
settings.gradle.kts        optional; without it, the folder's name is the project's name, and so the plugin's identifier
src/main/java/             the plugin in Java: one class that extends Plugin, settings interfaces, modules of its own
src/main/kotlin/           or the same in Kotlin, with the Kotlin JVM plugin applied; one language per plugin
src/main/lua/              optional Lua of the plugin's own, shipped under src/: core/levels.lua is src/core/levels.lua
src/main/resources/        PNG images and Ogg Vorbis sounds, shipped as resources/
src/dev/java/              optional development helpers, which never ship: one class may extend DevPlugin
src/dev/kotlin/            or the same in Kotlin
src/dev/lua/               optional Lua for the development helpers, under dev/ in the development folder
src/test/lua/              optional *_test.lua files, run against the plugin as it ships
```

The Oneiro Gradle plugin applies the `java` plugin, sets the Java toolchain to the JDK that Oneiro compiles with, and
adds the RS3 bindings, Oneiro's API (`oneiro-api`) and JSpecify to the compile classpath. A plugin written in Kotlin
applies the Kotlin JVM plugin beside it, `kotlin("jvm")`, which compiles with the same toolchain. The
[README](../README.md#getting-started) names the versions of each that Oneiro needs. A plugin is written in one
language: one with both Java and Kotlin sources fails to build.

## The `oneiro { }` block

```kotlin
version = "1.2.0"

oneiro {
    stubs {
        local(file("../rs3-api-types"))
    }
    plugin {
        name = "Herb Tracker"
        contactEmail = "plugins@example.org"
        settingsIcon = true
        dependencies {
            required("prettyui", 6398181) {
                types { local(file("lua-types/prettyui")) }
            }
        }
        permissions {
            optional(chatAccess)
        }
        urls {
            url("prices", "https://prices.example.org")
        }
    }
    libraries {
        vendored("zyklib") {
            remote("https://codeberg.org/ZyklopMarco/runescape-api-zyklib", "cf480d04a454aea1866953f3389fdd8dfc6aaed5")
            from("src")
        }
    }
}
```

| Setting | Meaning |
| --- | --- |
| `version` (the project's) | The manifest's version: digits and dots, as Mod.io requires. |
| `stubs { local(dir) }` | Generate the bindings from a checkout of rs3-api-types on this machine. |
| `stubs { remote(url, commit) }` | Fetch the stubs from a repository on GitHub, Codeberg or GitLab at a full commit, over HTTPS, into Gradle's cache; the same bindings on every machine. The address is the repository's own page: `https://github.com/<owner>/<repository>`, `https://codeberg.org/<owner>/<repository>` or `https://gitlab.com/<group>/<project>`. |
| `stubs { remote(url) }` | The latest commit of the repository's default branch, looked up at most once a day and on every explicit `oneiroBindings` run; an `--offline` build uses the commit it last looked up. |
| `plugin { id = "..." }` | The identifier: the dev link's name, the zip's name and the prefix of event subscriptions. Lowercase words joined by hyphens; by default the project's name. |
| `plugin { name, contactEmail }` | What people see, and the address the legal sections require. Both are required. |
| `plugin { settingsIcon, ribbonIcon }` | The manifest's `hasSettingsIcon` and `hasRibbonIcon`: icons whose layers the `SettingsLayerReady` and `ContentLayerReady` events fill. Subscribing to one of those events without its icon does not compile. |
| `plugin { dependencies { required(alias, modId) } }` | A library plugin the plugin cannot do without, by its Mod.io ID, which the client exposes as the Lua global `alias`; `optional(alias, modId)` one it uses when present, and `development(alias, modId)` one only the dev source set uses. Each may take a block, as [Library plugins](#library-plugins) explains. |
| `types { ... }`, `devFolder(folder)` | In a dependency's block: where the LuaCATS types its classes are generated from come from; and the folder of a plugin on this machine that the development folder's manifest names instead of the Mod.io ID. `required(alias) { devFolder(folder) }` names a dependency not published yet, which `checkPlugin` does not let ship. |
| `plugin { permissions { optional(chatAccess) } }` | `httpAccess`, `chatAccess` or `trackingAccess`. Prefer `optional`, and check `CurrentPlugin.HasPermission` before using what it unlocks; `required` makes the plugin unusable without it. Code that needs an undeclared permission does not compile, and Oneiro never adds one on its own. |
| `plugin { urls { url(alias, url) } }` | A base URL the plugin may reach with `httpAccess`, read in code as `Urls.<alias>`. |
| `libraries { vendored(name) { ... } }` | A Lua library the plugin vendors: copied into it and bound from its annotations, as [Vendored libraries](#vendored-libraries) explains. The name, lowercase letters and digits, names the package of its bindings, `org.adelheid.oneiro.lib.<name>`. |
| `libraries { development(name) { ... } }` | A library only the dev source set uses, which goes into the development folder alone. |
| `remote(url, commit)` or `local(dir)` | In a library's block or a dependency's `types { }`: its code, a repository on GitHub, Codeberg or GitLab at a full commit, or a checkout on this machine. |
| `from(folder)`, `licence(file)`, `overrides(file)` | In a library's block: the folder of it whose Lua goes to the plugin's root, its root by default; its licence's file, by default the one file at its root named like a licence; and an overrides file for its bindings. `from` and `overrides` work the same way in a dependency's `types { }`. |
| `rootPackage` | The package whose subpackages become folders under `src/`; by default the plugin class's package. |
| `warningsAsErrors` | Whether a warning, such as a comment Oneiro cannot place or a use moderation reviews closely, fails the build. |
| `devPlugins` | The client's `dev_plugins` folder; by default the beta client's on Windows, `C:\ProgramData\Jagex\RuneScape-BETA\dev_plugins`. |
| `lua { executable = file(...) }` | An installed Lua 5.4 for `testLua`, instead of the bundled Lua 5.4.8. |

Oneiro fetches nothing the build does not name: without a `stubs { }` block, `oneiroBindings` fails with a message that
shows both forms and the commit this Oneiro was tested against. Stubs at another commit get a warning, because the
overrides Oneiro applies to them may no longer match.

## The tasks

| Task | What it does | Output |
| --- | --- | --- |
| `oneiroBindings` | Generates the RS3 bindings from the stubs, in a worker process, and compiles them; for a plugin written in Kotlin, a member that takes a hook has a form for each callback the hook takes, so that the callback can be a trailing lambda | `build/oneiro/bindings/rs3-bindings.jar`, and a sources jar for the IDE |
| `oneiroLibraries` | Fetches the libraries the plugin vendors and stages them as they ship, with their licences, and checks that they may; and stages the types of the library plugins it depends on | `build/oneiro/libraries/` |
| `oneiroLuaBindings` | Generates classes from the LuaCATS annotations of the libraries, of the library plugins' types and of the plugin's own Lua, `src/main/lua/` and `src/dev/lua/`, in a worker process, and compiles them; the main set's and its libraries' are on the main source set's classpath, the dev set's and its libraries' on the dev source set's alone | `build/oneiro/lua-bindings/`: `main-bindings.jar`, `dev-bindings.jar` and a jar per library under `libraries/`, each with a sources jar |
| `oneiroSources` | Writes the typed `Resources` class from `src/main/resources/` and the `Urls` class from the manifest's URLs, and lists the packages of the plugin's code, whose names the classes of its own Lua cannot take | `build/generated/sources/oneiro/java/main` |
| `compileJava` | javac, with Oneiro as a javac plugin: once javac has analysed the sources without errors, Oneiro checks them and writes the Lua | `build/oneiro/lua/main/` |
| `oneiroKotlin` | For a plugin written in Kotlin, after Kotlin's own `compileKotlin`: Oneiro's Kotlin front end checks the sources and writes the Lua, in a worker process | `build/oneiro/lua/main/` |
| `pluginManifest` | Writes `plugin.json` from the `plugin { }` block, with every rule of the manifest checked, among them those on aliases, against the client's globals that the stubs declare | `build/oneiro/manifest/plugin.json` |
| `assemblePlugin` | The plugin exactly as it ships | `build/oneiro/plugin/`: `main.lua`, `plugin.json`, `src/` with the plugin's own Lua, `utils/` with the helpers Oneiro's Lua uses, `resources/`, and each vendored library's Lua with its licence under `licences/` |
| `checkPlugin` | Checks that folder; part of `check` | `build/oneiro/check-plugin.txt` |
| `packagePlugin` | Zips that folder, with `main.lua` and `plugin.json` at the top; part of `assemble` | `build/distributions/<id>-<version>.zip` |
| `assembleDevPlugin` | The shipped folder plus the dev source set's Lua, compiled and its own, under `dev/`, and its libraries, with a manifest of its own, which names each dependency's `devFolder` where it has one and requires the development dependencies too; part of `assemble` where `linkDevPlugin` links it, so that each build refreshes what the client loads | `build/oneiro/dev-plugin/` |
| `linkDevPlugin` | Links the plugin into the client's `dev_plugins` folder under its identifier: the dev folder when there is a dev source set or its manifest differs from the shipped one, the shipped folder otherwise | A junction on Windows, a symbolic link elsewhere |
| `testLua` | Runs each `*_test.lua` under `src/test/lua/` against the shipped folder, in a worker process; part of `check` | `build/oneiro/test-lua.txt` |
| `explainError` | Shows the plugin's errors in text copied from the client's console as the source lines they came from: `./gradlew explainError --input=console.txt` | Printed; it reads the line maps the compile writes to `build/oneiro/line-maps/` |

`./gradlew build` compiles, checks, tests and zips; `./gradlew linkDevPlugin` links the plugin into the client once,
after which each build updates what the client loads. In the client console, `prf` finds a newly linked plugin,
`pon <id>` enables it and `prl <id>` reloads it after a build.

The Lua of every file depends on the others, so the compile is not incremental: it compiles every file each time.
The zip is reproducible: fixed timestamps and sorted entries, so the same sources give the same bytes.

## Compiling

Oneiro compiles a plugin where the compiler of its language runs, so a construct it cannot translate fails the build
at the same step as a type error, and appears in the IDE's build output like any other compile error:

- **Java:** Oneiro runs inside the ordinary `compileJava`, as a javac plugin, once javac has analysed the sources
  without errors, and reports in javac's format, `HerbPlugin.java:12: error: message`.
- **Kotlin:** Kotlin's own `compileKotlin` runs first, so a type error is reported as Kotlin reports it; then
  `oneiroKotlin` lowers the sources with Oneiro's Kotlin front end, which runs on the Kotlin Analysis API in a worker
  process of its own, and reports as kotlinc does, `e: file:///.../HerbPlugin.kt:12:5 message`, which IDEs link to the
  line.

Every error is reported in one run, and no Lua is written while any remains. The checks against the manifest happen
here too:

- A member that needs a permission (`Chat.AddMessage`, `Event.ChatMessage`, `HTTPRequest`, `Player.GetTrackingID`)
  does not compile unless the `permissions { }` block declares it.
- Subscribing to `SettingsLayerReady` or `ContentLayerReady` does not compile unless the matching icon is set.
- A development-only member, such as `ExecuteConsoleCommand`, compiles only in the dev source set.
- `Resources.icons.herb` and `Urls.prices` are fields of generated classes, so a missing file or an undeclared alias
  does not compile.

A use that Jagex's moderators review closely compiles with a warning that names what they look at, so that the
plugin meets it before it is submitted rather than in a moderator's report:

| Area | Uses |
| --- | --- |
| `chat` | `Chat.AddMessage`, subscribing to `ChatMessage` |
| `http` | Creating an `HTTPRequest` |
| `tracking` | `Player.GetTrackingID` |
| `mini-menu` | `MiniMenu.Swap`, `MiniMenu.Add`, assigning `MiniMenuEntry.overrideText` |
| `other-players` | `PlayerList.All`, `ByUID`, `ByName`, `ByDistance` and `Filter`; not `PlayerList.Self` |

In Java:

```text
HerbPlugin.java:28: warning: Subscribing to ChatMessage uses the chat, which Jagex's moderators review closely: the
Mod.io description must say what the plugin uses chatAccess for, and other players' messages are information about
them, which a plugin must never export. Once the plugin meets this, @SuppressWarnings("oneiro:chat") on the method
or class silences this warning.
```

In Kotlin:

```text
w: file:///.../HerbPlugin.kt:28:5 Subscribing to ChatMessage uses the chat, which Jagex's moderators review closely:
the Mod.io description must say what the plugin uses chatAccess for, and other players' messages are information about
them, which a plugin must never export. Once the plugin meets this, @Suppress("oneiro:chat") on the function, class
or file silences this warning.
```

Once the plugin meets what a warning names, `@SuppressWarnings("oneiro:<area>")` on a method or class around the
use silences that area's warnings there, as it silences javac's own; in Kotlin, `@Suppress("oneiro:<area>")` on a
function, class or file does. The dev source set is never warned about, since
it never ships. With `warningsAsErrors`, a warning nobody has silenced fails the build.

## Where the Kotlin front end's libraries come from

The Kotlin front end runs on the Kotlin Analysis API and the IntelliJ platform modules it needs, which JetBrains
publishes only to its own repositories, not to Maven Central. A plugin's build resolves them from there:

- **A build that declares its repositories in `build.gradle.kts`**, as the README's does, needs nothing more. When the
  Kotlin JVM plugin is applied, Oneiro's Gradle plugin adds the two JetBrains repositories to it, each asked only for
  those artifacts, and those artifacts only from it, so every other dependency resolves as before.
- **A build that declares its repositories in `settings.gradle.kts`**, under `dependencyResolutionManagement`, adds the
  two there, as Gradle then reads no others:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // The Kotlin Analysis API and the IntelliJ platform, which Oneiro's Kotlin front end runs on
        exclusiveContent {
            forRepository { maven("https://cache-redirector.jetbrains.com/intellij-dependencies") }
            filter {
                includeModuleByRegex("org\\.jetbrains\\.kotlin", ".*-for-ide")
                includeGroup("com.intellij.platform")
                includeGroupByRegex("org\\.jetbrains\\.intellij\\.deps.*")
            }
        }
        exclusiveContent {
            forRepository { maven("https://cache-redirector.jetbrains.com/intellij-repository/releases") }
            filter { includeGroupByRegex("com\\.jetbrains\\.intellij\\..*") }
        }
    }
}
```

Without them, the build stops before `oneiroKotlin` with `Could not resolve all dependencies for configuration
':oneiroKotlinTool'`, naming an artifact it could not find, such as `com.jetbrains.intellij.platform:core-impl`. The
libraries come to about 100 MB, which Gradle downloads once and keeps in its cache.

## The plugin class, events and settings

The one class that extends `Plugin` becomes `main.lua`. Its members, fields and methods in Java or properties and
functions in Kotlin, are locals of `main.lua`; the body of `startUp()` runs when the client loads the file, after every
member is defined; each `@Subscribe` method is then subscribed under `"<id>.<method>"`; and the global `PluginShutdown`
unsubscribes them and runs `shutDown()`. In Java:

```java
public final class HerbPlugin extends Plugin {

    private HerbConfig settings;
    private int ticks;

    @Override
    protected void startUp() {
        log("Herb tracker started");
    }

    @Subscribe
    public void onServerLogic(ServerLogicEventPayload tick) {
        ticks++;
    }
}
```

In Kotlin:

```kotlin
class HerbPlugin : Plugin() {

    private lateinit var settings: HerbConfig
    private var ticks = 0

    override fun startUp() {
        log("Herb tracker started")
    }

    @Subscribe
    fun onServerLogic(tick: ServerLogicEventPayload) {
        ticks++
    }
}
```

Either becomes:

```lua
local ticks = 0

local function onServerLogic()
    ticks = ticks + 1
end

log("Herb tracker started")

Event.ServerLogic.Subscribe("herbs.onServerLogic", onServerLogic)

-- The client calls PluginShutdown when the plugin is disabled, and requires it to be global.
function PluginShutdown()
    Event.ServerLogic.Unsubscribe("herbs.onServerLogic")
end
```

An interface annotated `@ConfigGroup` is the plugin's settings: a module of typed `PersistentDB` accessors, one per
`@ConfigItem` method, kept under `"<group>.<keyName>"`. A field of its type in the plugin class needs no value, and in
Kotlin is a `lateinit var`; every call through it calls the module. The settings page itself is the plugin's to build,
in `SettingsLayerReady`, with the `ui` bindings. The exact rules, and every rejection, are in
[java-subset.md](java-subset.md#the-plugin-class-and-settings) and
[kotlin-subset.md](kotlin-subset.md#the-plugin-class-and-settings).

## Development helpers

Code that must never ship, such as cheats that teleport or spawn items, goes in `src/dev/java/`, or in
`src/dev/kotlin/` for a plugin written in Kotlin. That source set
compiles against the main one, and its Lua goes under `dev/` in `build/oneiro/dev-plugin/`, never into the zip. Oneiro
reads the main set's sources with it, so that its code sees each main class as the main set's Lua makes it, and lowers
only the dev set's. What the main set's Lua makes of a class is the main set's code alone to decide, so dev code that
would change it is an error: reading the order of an enum whose constants the main set's Lua makes strings, or
creating or extending a Java class it makes a table of static members. One of
its classes may extend `DevPlugin`: it works like the plugin class, becomes `dev/main.lua`, which the development
folder's `main.lua` requires last, subscribes its methods under `"<id>.dev.<method>"`, and wraps `PluginShutdown` so
that the plugin's own shutdown runs first. When the dev source set exists, `linkDevPlugin` links the development
folder, so the client runs the shipped files plus the helpers; it does too when a dependency names a `devFolder` or is
for development alone, so that the client reads the development folder's manifest.

## The plugin's own Lua

Lua the plugin keeps as Lua, such as a module of a Lua plugin being ported, goes under `src/main/lua/`, laid out as it
ships under `src/`: `src/main/lua/core/levels.lua` ships as `src/core/levels.lua`, and Lua loads it with
`require("src/core/levels")`. It ships as it is written, so Jagex's moderators read it as the plugin's, and
`checkPlugin` and `testLua` cover it as they cover the Lua Oneiro writes.

Each file starts with its path, as the files Oneiro writes do: the client names a file in its errors by its first line,
and `explainError` shows a place in it as that line of the file under `src/main/lua/`, with its code. `checkPlugin`
checks that it does.

```lua
-- src/core/levels.lua
-- The levels an arrangement scores, from 0 (Perfect) up.

local Levels = {}

Levels.PERFECT = 0

---@param penalty integer the sum of the slots' distances
---@return integer level
function Levels.fromPenalty(penalty)
    return penalty // 2
end

return Levels
```

`oneiroLuaBindings` generates classes from the files' LuaCATS annotations, the ones Lua Language Server reads, so that
Java and Kotlin call the Lua as Lua calls it. A module, a file that returns a table, is a class of the plugin's package
for its folder, named by the module's `---@class` or else by its file, so `src/main/lua/core/levels.lua` is
`<root>.core.Levels`, which a class of `<root>.core` names without an import:

```java
int level = Levels.fromPenalty(penalty) + Levels.PERFECT;
```

That line becomes:

```lua
local Levels = require("src/core/levels")

local level = Levels.fromPenalty(penalty) + Levels.PERFECT
```

The annotations give the types, and the generator infers nothing they leave out: a parameter or field without a type
is an `Object`, which Java and Kotlin can pass on but not use, so the functions and fields the plugin's code reaches
want `---@param`, `---@field` and `---@return` tags. A type may be one of the RS3 API's, `---@param parent
ui.Component`. Several values that the `---@return` tags name are a record named after the function,
`Stats.SummariseResult` for `Stats.summarise`, and a class with a `new` and colon functions is a class of objects. The
bindings belong to the build, never to the plugin's sources: a class of the plugin's that carries one of their
annotations, such as `@LuaModule`, does not compile, nor does a `native` method or, in Kotlin, an `external` function,
since the Lua's annotations say what it is. A class whose Lua would be one of these files does not compile either.

`src/dev/lua/` is the same for the dev source set: its files go under `dev/` in the development folder and never into
the zip, its classes are on the dev source set's classpath alone, and its annotations may name the main set's types.

Modules of one name in different folders are different classes, `<root>.core.Settings` and `<root>.ui.Settings`. A
dotted `---@class` name nests in the class its first part names, `core.Levels.Options` in the module `core.Levels`, and
is otherwise a class of its file's package. Some names Java cannot give a class, and `oneiroLuaBindings` fails with the
file of each:

- a folder whose name no package can have, such as `ui-parts` or `new`;
- a class with the name of a package, such as `---@class core` beside the folder `core/`, or beside the plugin's code
  in `<root>.core`, since Java has no class and package of one name;
- a class that `src/main/lua/` and `src/dev/lua/` both have, since they share the root package.

## Vendored libraries

A Lua library that plugins copy into themselves, such as
[Zyklib](https://codeberg.org/ZyklopMarco/runescape-api-zyklib), is vendored: the build names it, and its code ships as
part of the plugin, unchanged, so moderators read it as the plugin's.

```kotlin
oneiro {
    libraries {
        vendored("zyklib") {
            remote("https://codeberg.org/ZyklopMarco/runescape-api-zyklib", "cf480d04a454aea1866953f3389fdd8dfc6aaed5")
            from("src")
        }
    }
}
```

`oneiroLibraries` fetches the commit into Gradle's cache once, or reads a checkout on this machine named with
`local(file(...))`, and copies the Lua files of the `from` folder to the plugin's root, in the layout the library
documents: Zyklib's `src/zyklib.lua` ships as `zyklib.lua`, which Lua loads with `require("zyklib")`. Its licence ships
under `licences/zyklib/`. A commit is always a full one, since a library's code ships and its types shape the plugin's;
there is no form that follows a branch.

`oneiroLuaBindings` generates the library's classes from its own LuaCATS annotations, in
`org.adelheid.oneiro.lib.<name>`, each module a class named as its `require` binds it: Zyklib declares `---@class
zyklib`, so its table is the class `zyklib`, and the tables it holds are nested in it, as Lua reaches them.

```java
import org.adelheid.oneiro.lib.zyklib.zyklib;

int starts = zyklib.objects.getOrElseUpdate(counts, "starts", () -> 0);
```

That line becomes:

```lua
local zyklib = require("zyklib")

local starts = zyklib.objects.getOrElseUpdate(counts, "starts", function()
    return 0
end)
```

The plugin's own Lua may name a library's types too. Where the annotations leave something out that Java needs, such as
the names of the several values a function returns, `overrides(file(...))` names a file of the lines Oneiro's own
overrides take, but for those of the client alone; the build's warnings say where it would help.

The build fails for a library without a licence, since nobody may pass on its code without one; for a library whose
files would ship where the plugin's own go, as `main.lua`, `src/`, `utils/`, `resources/`, `dev/` or `licences/`, or where
another library's do; and, in `checkPlugin`, for a library that no Lua of the plugin's requires, which moderators would
read for nothing. A library declared with `development(name)` goes into the development folder alone, and only the dev
source set has its classes.

Zyklib's `lifecycle.setupGlobals` wraps a `PluginShutdown` that exists when it runs, but the one Oneiro writes is
defined at the end of `main.lua`, after `startUp()`, and replaces the wrapper; a plugin built with Oneiro calls
`zyklib.lifecycle.notifyShutdown()` from its `shutDown()` instead.

## Library plugins

A library plugin, such as PrettyUI (Mod.io 6398181), ships on its own: the player installs it, and the client gives
each plugin whose manifest names it the library's table as a Lua global, named by the alias the manifest gives it. None
of its code ships with the plugin. Its classes come from LuaCATS types the build names: definition files written from
its public docs, kept with the plugin's sources and never shipped, or a repository of community types.
[Writing a definition file](using-lua.md#writing-a-definition-file) shows how to write one.

```kotlin
oneiro {
    plugin {
        dependencies {
            required("prettyui", 6398181) {
                types { local(file("lua-types/prettyui")) }
            }
        }
    }
}
```

`types { }` takes what a vendored library's block takes: `local(file(...))` or `remote(url, commit)`; `from(folder)`,
for a folder such as `library` in a repository laid out for LLS-Addons; and `overrides(file(...))`. `oneiroLuaBindings`
generates the classes in `org.adelheid.oneiro.lib.<alias>`. The types declare the library's global, `---@class
prettyui` with `prettyui = {}`, or give the `main.lua` that returns its table, as the library's own code does; either is
the class named like the alias, which stands for the global, so `prettyui.version` in Java and Kotlin is
`prettyui.version` in Lua, and the tables it holds are classes nested in it. Its fields are final: the global is the
plugin's copy of the library's table, which an assignment would not change for the library. Its constant `ALIAS` is the
alias, which compiles to the string itself, to compare with the identifier an `OptionalDependencyChanged` payload
carries. A dependency without `types { }` has no classes, so only the plugin's own Lua can use it, and code that uses a
library the build does not declare does not compile.

### Optional dependencies

The player may not have an optional dependency, or may turn it off while the plugin runs, which the
`OptionalDependencyChanged` event tells. Its class has `isLoaded()`, which in a condition is the global itself:

```kotlin
dependencies {
    optional("prettyui", 6398181) {
        types { local(file("lua-types/prettyui")) }
    }
}
```

```java
if (prettyui.isLoaded()) {
    log("PrettyUI " + prettyui.version);
}
```

```lua
if prettyui then
    log(formatStr("PrettyUI {}", prettyui.version))
end
```

As a value, elsewhere, it is `prettyui ~= nil`. Code that reaches the global, a field, function or constructor of the
class or of a class nested in it, compiles only where a check reaches it in the same function: inside
`if (prettyui.isLoaded())` or a `while` on it, in the `else` of `if (!prettyui.isLoaded())`, on the right of
`prettyui.isLoaded() &&`, or after `if (!prettyui.isLoaded()) return;` or another exit. A lambda, a method or function,
and a class stop the search, since their code runs later, when the library may be gone; their code checks again. A
value the global handed out needs no check. An unchecked use is an error that shows the check, in Java and Kotlin
alike.

### Versions, folders and the dev source set

Neither the client nor the manifest knows a dependency's version, so the types describe the version they were written
for, and the player may have another; Oneiro adds no check of its own. A library that exposes its version, as
PrettyUI's `prettyui.version` does, is read like any field, so the plugin can check it when it starts:

```java
private static final String TESTED_WITH = "1.4.0";

@Override
protected void startUp() {
    if (!prettyui.version.equals(TESTED_WITH)) {
        log("Tested with PrettyUI " + TESTED_WITH + "; the player has " + prettyui.version + ".");
    }
}
```

Where the player's version lacks a name the types describe, the client's error says that the name is nil, and
`explainError` adds that the library's version may be why.

`devFolder("prettyui")` in a dependency's block names the folder of a plugin on this machine, by its name in the
client's plugin folders, which the development folder's manifest names in place of the Mod.io ID: a local copy of a
library, or one not published yet. One not published yet is named by its folder alone,
`required("rsprite") { devFolder("rsprite") }`: the plugin can be developed against it, but `checkPlugin` fails until
it names the Mod.io ID too, since players' clients find a dependency by its ID. `development("probe", 5151)` names a
dependency only the dev source set uses: only the development folder's manifest requires it, and only the dev source
set has its classes.

An alias is a Lua name that no global of Lua's or of the client's has already, such as `print` or `Player`, since the
client exposes the dependency as the global of its alias, and a dependency is declared once, of one kind. An alias whose
dependency has types names the package of its classes, so it is no vendored library's name, nor a word Java reserves.

## What `checkPlugin` checks

`checkPlugin` reads the shipped folder as Mod.io would receive it and fails the build on any of these:

- `main.lua` or `plugin.json` is missing.
- A dependency names only a folder on this machine, with `devFolder`, and no Mod.io ID.
- A file is something other than `main.lua`, `plugin.json`, a Lua file under `src/`, a helper Oneiro writes in
  `utils/`, a PNG or Ogg Vorbis file under `resources/`, or a file of a vendored library, its Lua or its licence.
- A Lua file under `src/`, or a helper in `utils/`, does not start with its path, `-- src/core/levels.lua`.
- A `require` names a module that is not in the plugin.
- No Lua of the plugin's, its own or the Lua Oneiro writes, requires a vendored library.
- A file uses a member of the client's that is for development only, such as `ExecuteConsoleCommand`, or, outside a
  vendored library, one that needs a permission the manifest does not declare, such as `HTTPRequest` without
  `httpAccess`. The compiler holds the Lua it writes to this already; the check holds the Lua it did not write to it,
  by the members' names, the same the compiler knows from the bindings. It reads the text as it is, so a name in a
  comment counts too. A library's code may name members of every permission, in parts the plugin never calls, as
  Zyklib names `HTTPRequest` in its HTTP helpers, so a plugin needs no permission for vendoring it.
- An image is larger than 1024 by 1024 pixels, or a resource is not really a PNG or an Ogg Vorbis file.

The manifest's own rules are checked when it is written: the identifier, name, version and contact address; known
permissions, none both required and optional; dependency aliases that are Lua names no global of Lua's or of the
client's has, each declared once, with a Mod.io ID or a folder on this machine;
URLs that are `http` or `https` with a host; and base URLs only together with `httpAccess`, and `httpAccess` only with
a base URL.

## Lua tests

`testLua` runs each `*_test.lua` under `src/test/lua/` in a fresh Lua state, with the shipped folder and the tests
folder on `package.path`, so a test loads a module, Oneiro's or the plugin's own, with `require("src/tally")` and a
helper next to it with `require("helper")`. A test passes when it runs to its end; an error, such as a failed
`assert`, fails it and the build. The tests run outside the client, so the client's tables are absent; a test stands
in for what it needs. `formatStr` and `require` are the exceptions: stand-ins that reproduce the client's behaviour are
defined before each test. The client's `require` passes on only a table, and gives nil for a file that returns
anything else, such as a function, or nothing; the stand-in gives nil for a file that returns nothing, and makes any
other value an error, so that a test that requires such a file fails where the plugin would. Tests never ship.

## Explaining a client error

When the plugin runs into an error, the client's console shows it against the Lua, with the calls that led to it:

```text
[ERROR] in Herb Tracker(-1): [string "-- src/tally.lua..."]:31: attempt to perform arithmetic on a nil value (field 'count')
stack traceback:
	[string "-- src/tally.lua..."]:31: in method 'add'
	[string "-- main.lua..."]:19: in function 
```

The client names a file by its first line, which is why every Lua file Oneiro writes starts with its path. To see your
source instead, copy the console's text into a file, as much of it as you like, and run:

```sh
./gradlew explainError --input=console.txt
```

```text
src/main/java/com/example/herbs/Tally.java:31: attempt to perform arithmetic on a nil value (field 'count')
	at src/main/java/com/example/herbs/Tally.java:31: count += amount;
	at src/main/java/com/example/herbs/HerbPlugin.java:25: tally.add(1);

1 error of Herb Tracker, newest first, as the console lists them.
```

Each place is the file and line of the source it came from, a `.java` or a `.kt` file, with that line's code, in the
form IDEs link; a place in the plugin's own Lua is the line of its file under `src/main/lua/`, and a place in one of
the helpers Oneiro writes keeps its file and line, named as the helper, `utils/ordered_table.lua:12 (orderedTable, a
helper Oneiro writes)`, after which the next place is the code that called it. The task picks the
plugin's errors by its name and leaves out everything else, including other plugins' errors, whose files can share
a path with this plugin's. It reads the line maps of the latest build, so it explains the Lua that build wrote: after
changing the code, build and reload the plugin, and reproduce the error, before explaining it.

An error that the client raises itself names C++ types rather than those of your code. Most come from a part of the
API that the client has differently from the docs, which Oneiro's bindings follow, for instance after a client update.
For the client's messages that Oneiro knows, the task adds what the message means and what to do instead: an argument
the client requires and the call leaves out, a form of a function the client lacks, a value a field does not take, a
field of a value the client handed out, and a name the client lacks.

```text
stack index 4, expected number, received no value: not a numeric type (bad argument into 'void(float, float, unsigned char)')
	at src/main/java/com/example/herbs/Growth.java:22: progress.Start(0, 10);
	In the client, Start requires argument 3, which this call leaves out: Oneiro's bindings follow the docs, which let it be left out. Pass it.
```
