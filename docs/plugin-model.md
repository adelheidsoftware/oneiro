# The plugin model and the build

How a plugin written in Java becomes an RS3 plugin: the project's layout, the `oneiro { }` block that configures it, the
tasks that turn it into the folder the client loads and the zip that goes to Mod.io, and the checks on the way. What
each Java construct becomes is in [java-subset.md](java-subset.md).

## A plugin project

```text
build.gradle.kts           plugins { id("org.adelheid.oneiro") }, the version, and the oneiro { } block
settings.gradle.kts        rootProject.name, which is the plugin's identifier unless the build names another
src/main/java/             the plugin: one class that extends Plugin, settings interfaces, modules of its own
src/main/resources/        PNG images and Ogg Vorbis sounds, shipped as resources/
src/dev/java/              optional development helpers, which never ship: one class may extend DevPlugin
src/test/lua/              optional *_test.lua files, run against the plugin as it ships
```

The Oneiro Gradle plugin applies the `java` plugin, sets the Java toolchain to 25 (Gradle finds an installed JDK 25 or,
with the foojay resolver, downloads one), and adds the RS3 bindings, Oneiro's API (`oneiro-api`) and JSpecify to the
compile classpath. The Gradle that runs the build only needs Java 17.

## The `oneiro { }` block

```kotlin
version = "1.2.0"

oneiro {
    stubs {
        local(file("../runescape-api-stubs"))
    }
    plugin {
        name = "Herb Tracker"
        contactEmail = "plugins@example.org"
        settingsIcon = true
        dependencies {
            required("prettyui", 6398181)
        }
        permissions {
            optional(chatAccess)
        }
        urls {
            url("prices", "https://prices.example.org")
        }
    }
}
```

| Setting | Meaning |
| --- | --- |
| `version` (the project's) | The manifest's version: digits and dots, as Mod.io requires. |
| `stubs { local(dir) }` | Generate the bindings from a checkout of runescape-api-stubs on this machine. |
| `stubs { remote(url, commit) }` | Fetch the stubs from a GitHub repository at a commit, over HTTPS, into Gradle's cache; the same bindings on every machine. |
| `stubs { remote(url) }` | The latest commit of the repository's default branch, looked up at most once a day and on every explicit `oneiroBindings` run; an `--offline` build uses the commit it last looked up. |
| `plugin { id = "..." }` | The identifier: the dev link's name, the zip's name and the prefix of event subscriptions. Lowercase words joined by hyphens; by default the project's name. |
| `plugin { name, contactEmail }` | What people see, and the address the legal sections require. Both are required. |
| `plugin { settingsIcon, ribbonIcon }` | The manifest's `hasSettingsIcon` and `hasRibbonIcon`: icons whose layers the `SettingsLayerReady` and `ContentLayerReady` events fill. Subscribing to one of those events without its icon does not compile. |
| `plugin { dependencies { required(alias, modId) } }` | Another plugin, by its Mod.io ID, exposed as the Lua global `alias`; `required(alias, "folder")` names a plugin on this machine by its folder, and `optional(...)` a dependency the plugin can do without. |
| `plugin { permissions { optional(chatAccess) } }` | `httpAccess`, `chatAccess` or `trackingAccess`. Prefer `optional`, and check `CurrentPlugin.HasPermission` before using what it unlocks; `required` makes the plugin unusable without it. Code that needs an undeclared permission does not compile, and Oneiro never adds one on its own. |
| `plugin { urls { url(alias, url) } }` | A base URL the plugin may reach with `httpAccess`, read in code as `Urls.<alias>`. |
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
| `oneiroBindings` | Generates the RS3 bindings from the stubs, in a worker process on JDK 25, and compiles them | `build/oneiro/bindings/rs3-bindings.jar`, and a sources jar for the IDE |
| `oneiroSources` | Writes the typed `Resources` class from `src/main/resources/` and the `Urls` class from the manifest's URLs | `build/generated/sources/oneiro/java/main` |
| `compileJava` | javac, with Oneiro as a javac plugin: once javac has analysed the sources without errors, Oneiro checks them and writes the Lua | `build/oneiro/lua/main/` |
| `pluginManifest` | Writes `plugin.json` from the `plugin { }` block, with every rule of the manifest checked | `build/oneiro/manifest/plugin.json` |
| `assemblePlugin` | The plugin exactly as it ships | `build/oneiro/plugin/`: `main.lua`, `plugin.json`, `src/`, `resources/` |
| `checkPlugin` | Checks that folder; part of `check` | `build/oneiro/check-plugin.txt` |
| `packagePlugin` | Zips that folder, with `main.lua` and `plugin.json` at the top; part of `assemble` | `build/distributions/<id>-<version>.zip` |
| `assembleDevPlugin` | The shipped folder plus the dev source set's Lua under `dev/` | `build/oneiro/dev-plugin/` |
| `linkDevPlugin` | Links the plugin into the client's `dev_plugins` folder under its identifier: the dev folder when there is a dev source set, the shipped folder otherwise | A junction on Windows, a symbolic link elsewhere |
| `testLua` | Runs each `*_test.lua` under `src/test/lua/` against the shipped folder, in a worker process; part of `check` | `build/oneiro/test-lua.txt` |
| `explainError` | Shows the plugin's errors in text copied from the client's console as the Java lines they came from: `./gradlew explainError --input=console.txt` | Printed; it reads the line maps `compileJava` writes to `build/oneiro/line-maps/` |

`./gradlew build` compiles, checks, tests and zips; `./gradlew linkDevPlugin` links the plugin into the client once,
after which each build updates what the client loads. In the client console, `prf` finds a newly linked plugin,
`pon <id>` enables it and `prl <id>` reloads it after a build.

The Lua of every file depends on the others, so `compileJava` is not incremental: it compiles every file each time.
The zip is reproducible: fixed timestamps and sorted entries, so the same sources give the same bytes.

## Compiling

Oneiro runs inside the ordinary `compileJava`, so a construct it cannot translate fails the build at the same step as
a type error, in javac's format, and appears in the IDE's build output like any other compile error. Every error is
reported in one run, and no Lua is written while any remains. The checks against the manifest happen here too:

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
| `http` | `new HTTPRequest` |
| `tracking` | `Player.GetTrackingID` |
| `mini-menu` | `MiniMenu.Swap`, `MiniMenu.Add`, assigning `MiniMenuEntry.overrideText` |
| `other-players` | `PlayerList.All`, `ByUID`, `ByName`, `ByDistance` and `Filter`; not `PlayerList.Self` |

```text
HerbPlugin.java:28: warning: Subscribing to ChatMessage uses the chat, which Jagex's moderators review closely: the
Mod.io description must say what the plugin uses chatAccess for, and other players' messages are information about
them, which a plugin must never export. Once the plugin meets this, @SuppressWarnings("oneiro:chat") on the method
or class silences this warning.
```

Once the plugin meets what a warning names, `@SuppressWarnings("oneiro:<area>")` on a method or class around the
use silences that area's warnings there, as it silences javac's own. The dev source set is never warned about, since
it never ships. With `warningsAsErrors`, a warning nobody has silenced fails the build.

## The plugin class, events and settings

The one class that extends `Plugin` becomes `main.lua`. Its fields and methods are locals of `main.lua`; the body of
`startUp()` runs when the client loads the file, after every member is defined; each `@Subscribe` method is then
subscribed under `"<id>.<method>"`; and the global `PluginShutdown` unsubscribes them and runs `shutDown()`.

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
`@ConfigItem` method, kept under `"<group>.<keyName>"`. A field of its type in the plugin class needs no value; every
call through it calls the module. The settings page itself is the plugin's to build, in `SettingsLayerReady`, with
the `ui` bindings. The exact rules, and every rejection, are in
[java-subset.md](java-subset.md#the-plugin-class-and-settings).

## Development helpers

Code that must never ship, such as cheats that teleport or spawn items, goes in `src/dev/java/`. That source set
compiles against the main one, and its Lua goes under `dev/` in `build/oneiro/dev-plugin/`, never into the zip. One of
its classes may extend `DevPlugin`: it works like the plugin class, becomes `dev/main.lua`, which the development
folder's `main.lua` requires last, subscribes its methods under `"<id>.dev.<method>"`, and wraps `PluginShutdown` so
that the plugin's own shutdown runs first. When the dev source set exists, `linkDevPlugin` links the development
folder, so the client runs the shipped files plus the helpers.

## What `checkPlugin` checks

`checkPlugin` reads the shipped folder as Mod.io would receive it and fails the build on any of these:

- `main.lua` or `plugin.json` is missing.
- A file is something other than `main.lua`, `plugin.json`, a Lua file under `src/`, or a PNG or Ogg Vorbis file under
  `resources/`.
- A `require` names a module that is not in the plugin.
- A file calls `ExecuteConsoleCommand`.
- An image is larger than 1024 by 1024 pixels, or a resource is not really a PNG or an Ogg Vorbis file.

The manifest's own rules are checked when it is written: the identifier, name, version and contact address; known
permissions, none both required and optional; dependency aliases that are Lua names, each with a Mod.io ID or a folder;
URLs that are `http` or `https` with a host; and base URLs only together with `httpAccess`, and `httpAccess` only with
a base URL.

## Lua tests

`testLua` runs each `*_test.lua` under `src/test/lua/` in a fresh Lua state, with the shipped folder and the tests
folder on `package.path`, so a test loads a module with `require("src/tally")` and a helper next to it with
`require("helper")`. A test passes when it runs to its end; an error, such as a failed `assert`, fails it and the
build. The tests run outside the client, so the client's tables are absent; a test stands
in for what it needs. `formatStr` is the one exception: a stand-in that reproduces the client's behaviour is defined
before each test. Tests never ship.

## Explaining a client error

When the plugin runs into an error, the client's console shows it against the Lua, with the calls that led to it:

```text
[ERROR] in Herb Tracker(-1): [string "-- src/tally.lua..."]:31: attempt to perform arithmetic on a nil value (field 'count')
stack traceback:
	[string "-- src/tally.lua..."]:31: in method 'add'
	[string "-- main.lua..."]:19: in function 
```

The client names a file by its first line, which is why every Lua file Oneiro writes starts with its path. To see the
Java instead, copy the console's text into a file, as much of it as you like, and run:

```sh
./gradlew explainError --input=console.txt
```

```text
src/main/java/com/example/herbs/Tally.java:31: attempt to perform arithmetic on a nil value (field 'count')
	at src/main/java/com/example/herbs/Tally.java:31: count += amount;
	at src/main/java/com/example/herbs/HerbPlugin.java:25: tally.add(1);

1 error of Herb Tracker, newest first, as the console lists them.
```

Each place is the Java file and line it came from, with that line's code, in the form IDEs link. The task picks the
plugin's errors by its name and leaves out everything else, including other plugins' errors, whose files can share
a path with this plugin's. It reads the line maps of the latest build, so it explains the Lua that build wrote: after
changing the code, build and reload the plugin, and reproduce the error, before explaining it.
