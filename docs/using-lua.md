# Using Lua

A plugin built with Oneiro can use Lua that Oneiro does not write: a library plugin the player installs, such as [PrettyUI](https://prettyui.j3.gg/); a library copied into the plugin, such as [Zyklib](https://codeberg.org/ZyklopMarco/runescape-api-zyklib); and Lua of the plugin's own. Java and Kotlin call each of them through classes Oneiro generates from LuaCATS types, the annotations Lua Language Server reads. A file of types written once serves Java, Kotlin and Lua developers alike.

This page covers:
- the three kinds of Lua;
- where their types come from;
- how to write types for a library that has none, and how to check them and share them;
- the overrides file;
- Zyklib's shutdown.

The build's settings are in [plugin-model.md](plugin-model.md), and what each call compiles to is in [java-subset.md](java-subset.md#calling-existing-lua) and [kotlin-subset.md](kotlin-subset.md).

## Three kinds of Lua

| | Library plugin | Vendored library | The plugin's own Lua |
| --- | --- | --- | --- |
| Example | PrettyUI, Mod.io 6398181 | Zyklib | `src/main/lua/core/levels.lua` |
| Where its code is | With the player, who installs it from the Plugin Hub; none of it ships with the plugin | In the plugin: its files ship unchanged, with its licence | In the plugin |
| How Lua reaches it | The global the manifest's alias names, `prettyui` | `require("zyklib")` | `require("src/core/levels")` |
| How the build names it | `plugin { dependencies { required(alias, modId) { types { ... } } } }` | `libraries { vendored(name) { ... } }` | It doesn't: every file under `src/main/lua/` |
| Where its types come from | Definition files, written from its docs or taken from a community repository | Its own annotations | Its own annotations |
| Package of its classes | `org.adelheid.oneiro.lib.<alias>` | `org.adelheid.oneiro.lib.<name>` | The plugin's own, by folder |
| Read by moderators as the plugin's code | No | Yes | Yes |

- **A library plugin** is published on Mod.io on its own. The client loads it before the plugins that depend on it, and gives each of them a copy of the table its `main.lua` returns, as the global the manifest's alias names. The plugin is written against it without containing it. An optional one may be missing, or turned off while the plugin runs, so Oneiro compiles a use of it only where a check reaches it. [Library plugins](plugin-model.md#library-plugins) has the details.
- **A vendored library** is plain Lua that plugins copy into themselves, as its own docs say to. Oneiro fetches it at a full commit, copies its files to the plugin's root as the library lays them out, and ships its licence with it. [Vendored libraries](plugin-model.md#vendored-libraries) has the details.
- **The plugin's own Lua** is Lua kept as Lua, such as a module of a Lua plugin being ported. It ships as written, under `src/`. [The plugin's own Lua](plugin-model.md#the-plugins-own-lua) has the details.

## Where types come from

Every source of types is a folder of Lua files with LuaCATS annotations, which `oneiroLuaBindings` reads:

- **The Lua's own annotations**: `---@class`, `---@field`, `---@param` and `---@return` above its code, as Zyklib writes them. They are where a vendored library's and the plugin's own Lua's types come from.
- **Definition files**: Lua files that begin with `---@meta` and hold annotations and empty declarations, such as `function prettyui.SimpleView.new(parent, options) end`. Lua Language Server reads them as it reads code, and nothing runs them. A library plugin without annotations of its own needs them, and they live with the plugin's sources, in a folder the build names and never ships.
- **A community repository** of definition files. LLS-Addons, the collection Lua Language Server's addon manager installs from, keeps one repository per library, with its files under `library/`. The build names such a repository by its address and a full commit, from GitHub, Codeberg or GitLab, with `from("library")`.

Oneiro reads the types as they are written and infers nothing they leave out. A parameter or field without a type is an `Object`, which Java and Kotlin can pass on but not use, so the members the plugin calls want their types. How the types become Java:

| LuaCATS | Java |
| --- | --- |
| `string`, `integer`, `number`, `boolean` | `String`, `int`, `double`, `boolean` |
| `T[]` | `List<T>` |
| A field `name? T` | A field of the boxed type that may be null, `@Nullable Double` |
| A parameter `name? T` | A shorter form of the method, without it |
| A parameter `string\|prettyui.TextOptions` | One form for each type: `AddText(String)` and `AddText(prettyui.TextOptions)` |
| A union of string literals, of integer literals | `String`, `int` |
| `fun(button: prettyui.SimpleButton, active: boolean)` | `BiConsumer<prettyui.SimpleButton, Boolean>`; with a return, a `Function` or `BiFunction`; with three or more values, an interface of the package |
| A class of fields alone that a function takes, `prettyui.SimpleViewOptions` | A table the plugin builds: `new prettyui.SimpleViewOptions()` and its fields, which compile to `{ width = ..., height = ... }` |
| A table with a `new` and colon functions, `prettyui.SimpleView` | A class of objects: `new prettyui.SimpleView(layer, options)` is `prettyui.SimpleView.new(layer, options)`, and `view.Destroy()` is `view:Destroy()` |
| A `new` that returns another class | A static `create` of the table's class |
| A field whose description starts `(Read-only)` | A `final` field |
| `---@class Event<T, R>`, `---@generic T` | Java's generics, whose type variables take any value; `nil` as a type argument is `@Nullable Void` |
| Several `---@return` values with names | A record named after the function, `Stats.SummariseResult` for `Stats.summarise` |
| `---@deprecated` | `@Deprecated` |
| A type of the RS3 API, `ui.Component` | The RS3 bindings' class |

A type that neither the RS3 API nor any of the build's types declares is an `Object`, with a warning that names it.

## Writing a definition file

A library plugin without types needs a definition file before Java or Kotlin can call it. PrettyUI is such a library: its [docs](https://prettyui.j3.gg/) describe some thirty components, and its repository has no types. This file describes its `SimpleView`, abridged:

```lua
---@meta
-- PrettyUI's types, written from its public docs at https://prettyui.j3.gg/ for version 1.2.0.

---PrettyUI's table, which the client gives each plugin that depends on it as a copy of its own.
---@class prettyui
---@field version string The library's version, such as "1.2.1".
prettyui = {}

---Options of a SimpleView.
---@class prettyui.SimpleViewOptions
---@field x? number The view's horizontal position in its parent, in interface units.
---@field y? number The view's vertical position in its parent.
---@field width number The view's width, which it keeps until SetSize changes it.
---@field height number The view's height, which it keeps until SetSize changes it.
---@field scrollable? boolean Whether the content scrolls when it is taller than the view; true by default.

---What PrettyUI's content hosts have in common: a SimpleView, a window, a panel and others.
---@class prettyui.ContentHost
local ContentHost = {}

---Adds body text. Setting the text's content changes it.
---@param value string|prettyui.TextOptions the text, or its options
---@return ui.Text text
function ContentHost:AddText(value) end

---Adds a small button with text or an icon. The action is called on each click.
---@param content string|integer the button's text, or the sprite ID of its icon
---@param action fun(button: prettyui.SimpleButton, active: boolean) called on each click
---@param options? prettyui.SimpleButtonOptions
---@return prettyui.SimpleButton button
function ContentHost:AddSimpleButton(content, action, options) end

---A content host without a frame, on a layer of the plugin's.
---@class prettyui.SimpleView: prettyui.ContentHost
---@field scrollY number (Read-only) How far the content is scrolled.
prettyui.SimpleView = {}

---Creates a view in the parent.
---@param parent ui.Component the component to create the view in
---@param options prettyui.SimpleViewOptions
---@return prettyui.SimpleView
function prettyui.SimpleView.new(parent, options) end

---Destroys the view, its components and their tooltips.
function prettyui.SimpleView:Destroy() end
```

Each part has a job:

- **The global.** `---@class prettyui` above `prettyui = {}` declares the global the client gives the plugin. Its class is the one named like the manifest's alias, and the tables it holds, such as `prettyui.SimpleView = {}`, are classes nested in it. Its fields are final, as the global is the plugin's copy of the library's table.
- **Tables of objects.** A table with a `new` that returns its own class is a class with a constructor, and its colon functions are the objects' methods.
- **Options.** An options table is a `---@class` of fields alone, with `?` on each field that may be left out. Oneiro builds it in place, as a table constructor, so the Lua reads as PrettyUI's docs write it.
- **What several classes share.** PrettyUI's windows, panels and views all have the `Add…` methods. A class on a local table, `local ContentHost = {}`, holds them once, and the others extend it. The library needs no such table: it is a type, and nothing in Java or Lua reaches it by name.
- **Callbacks** are `fun(...)` types, with a name for each value, so that the Java parameter names read well.
- **Fields the plugin must not set.** A description that starts `(Read-only)` makes the Java field final, as the RS3 API's docs mark theirs.
- **What the docs do not say.** Leave it out rather than guess, or say where it comes from. PrettyUI's docs do not mention `version`, but the client has it, which the file says.

The build names the folder. Laying it out for LLS-Addons from the start, with the file under `library/`, means it can be offered there unchanged:

```kotlin
oneiro {
    plugin {
        dependencies {
            optional("prettyui", 6398181) {
                types {
                    local(file("lua-types/prettyui"))
                    from("library")
                }
            }
        }
    }
}
```

The plugin then uses PrettyUI as Lua would. In Java:

```java
@Subscribe
public void onContentLayerReady(ContentLayerReadyEventPayload event) {
    if (!prettyui.isLoaded()) {
        return;
    }
    ui.Component layer = event.component;
    prettyui.SimpleViewOptions options = new prettyui.SimpleViewOptions();
    options.width = layer.width;
    options.height = layer.height;
    prettyui.SimpleView view = new prettyui.SimpleView(layer, options);
    ui.Text count = view.AddText(clicks.label());
    view.AddSimpleButton("Count a click", (_, _) -> count.content = clicks.add());
}
```

In Kotlin:

```kotlin
@Subscribe
fun onContentLayerReady(event: ContentLayerReadyEventPayload) {
    if (!prettyui.isLoaded()) {
        return
    }
    val layer = event.component
    val options = prettyui.SimpleViewOptions()
    options.width = layer.width.toDouble()
    options.height = layer.height.toDouble()
    val view = prettyui.SimpleView(layer, options)
    val count = view.AddText(clicks.label())
    view.AddSimpleButton("Count a click") { _, _ -> count.content = clicks.add() }
}
```

Either becomes:

```lua
local function onContentLayerReady(event)
    if not prettyui then
        return
    end
    local layer = event.component
    local options = { width = layer.width, height = layer.height }
    local view = prettyui.SimpleView.new(layer, options)
    local count = view:AddText(clicks:label())
    view:AddSimpleButton("Count a click", function()
        count.content = clicks:add()
    end)
end
```

## Checking it

A definition file says what a library is, so check it against the library at three points:

1. **In the editor.** Add the folder to a Lua workspace's `.luarc.json`, as `"workspace.library": ["lua-types/prettyui/library"]` beside the RS3 API's types. Lua Language Server then reports mistakes in the file, and in Lua that uses it: a string where the file says a number, a method it does not describe. Lua developers get the same types this way.
2. **In the build.** `oneiroLuaBindings` reports what it cannot read, with its file and line, and a type it cannot find. The generated classes are in `build/oneiro/lua-bindings/libraries/main/<alias>-bindings-sources.jar`, with the descriptions as their Javadoc. They are worth a read: a class without the constructor you expected, or an `Object` where you expected a type, points at the file.
3. **In the client.** Build a plugin that calls what the file describes, link it, and run it with `prl <id>`. Compare what shows with the docs. A name the player's version of the library lacks is an error that it is nil; for a name in capitals, as PrettyUI's are, `explainError` adds that the library's version may be why. A wrong type usually shows up as an error inside the library's code, at the line that uses the value.

Neither the client nor the manifest knows a dependency's version, so say in the file which version it describes. If the library exposes its version, as PrettyUI's `prettyui.version` does, the plugin can check it when it starts, as [Versions, folders and the dev source set](plugin-model.md#versions-folders-and-the-dev-source-set) shows.

## Sharing it

A definition file helps more people when it lives where they look for it:

- **With the library.** Its author may take the file into the library's repository, or annotate the code itself, which keeps the types in step with each release. Ask first: it is their library.
- **In LLS-Addons**, from which Lua Language Server's addon manager installs types for Lua developers. A repository for it holds:
  - the file under `library/`;
  - a licence;
  - a `config.json` that names it, with the Lua patterns that make the addon manager suggest it.

  ```json
  {
      "name": "PrettyUI",
      "words": ["prettyui%.%u"]
  }
  ```

  The LLS-Addons [contributing guide](https://github.com/LuaLS/LLS-Addons/blob/main/CONTRIBUTING.md) has the rest:
  1. Open an issue that says what you are adding.
  2. In a fork, give the addon an `addons/<name>/info.json` with its `name` and `description`.
  3. Add your repository as a submodule at `addons/<name>/module`.

Once it is published, a build names it by its address and a full commit:

```kotlin
types {
    remote("https://github.com/<owner>/<repository>", "<full commit>")
    from("library")
}
```

## The overrides file

A few things Java needs, LuaCATS cannot say. For example, a function returns several values whose `---@return` tags name none of them, or the types name a type that no file declares. The build warns about these:

```text
zyklib.array.find returns 2 values, but neither its ---@return tags nor the overrides name them; only the first is bound.
```

`overrides(file("..."))` in a library's block, or in a dependency's `types { }`, names a file of lines that say how Java binds such things. Each line is `<fact> <target> [<argument> ...]`, then `#` and how the fact was checked:

```text
returns zyklib.array.find FindResult value index      # its ---@return tags describe both values but name neither
param zyklib.game_events.derived.interface interface integer   # its description: "The id of the interface in question"
```

The facts a library's file takes:

| Fact | Meaning |
| --- | --- |
| `returns <function> <Record> <name...>` | The record and component names of a function's several values |
| `param <function> <name> <type...>`, `type <member> <type...>` | The LuaCATS type of a parameter, a field or a function's value, where the types' is wrong or names a type nothing declares |
| `field <field> <type...>`, `function <Owner.Name> <fun>` | A field or a function the types lack |
| `nullable <member>`, `not-nullable <member>` | Whether a function's value or a field can be nil, where the types say otherwise |
| `read-only <class\|field>`, `writable <field>` | Whether Java may assign the field |
| `constructible <class>`, `not-constructible <class>` | Whether the plugin builds tables of the type |
| `singleton <class>`, `instance <class>` | Whether a table's colon functions belong to the table itself or to the objects its `new` makes |
| `java-name <class\|member> <name>`, `rename <member> <name>` | A Java name for a Lua name Java cannot use; the right name for one the types misspell |
| `superclass <class> <class>`, `operator <Class.name> <op> <fun>`, `values <enum> <NAME=value...>` | A parent the types leave out, an operator on the class's objects, an enum's values |
| `skip <class\|member>`, `note <class\|member> <text>` | A member not to bind, and a sentence for its Javadoc |

Keep the two apart. What the library is, true for any consumer, belongs in its types, where Lua developers get it too. How Java and Kotlin bind it belongs in the overrides file. A line the types have caught up with changes nothing, so a fix in the types needs no change to the file.

## Zyklib's shutdown

Zyklib tells its parts that the plugin is shutting down through `zyklib.lifecycle`. Its `setupGlobals()` wraps the `PluginShutdown` that exists when it runs. But Oneiro defines `PluginShutdown` at the end of `main.lua`, after `startUp()`, which replaces the wrapper. So a plugin built with Oneiro tells Zyklib itself, from `shutDown()`:

```java
@Override
protected void startUp() {
    zyklib.lifecycle.shutdown_event.subscribe(_ -> {
        log("Shutting down");
        return null;
    });
}

@Override
protected void shutDown() {
    zyklib.lifecycle.notifyShutdown();
}
```

The event's listeners return nothing: `nil`, which is `Void` in Java. Java's `return null;`, or Kotlin's final `null`, is what a Lua function does by ending, so the Lua leaves it out:

```lua
zyklib.lifecycle.shutdown_event:subscribe(function()
    log("Shutting down")
end)
```
