# Oneiro

Write RuneScape 3 plugins in Java, the way you would write a RuneLite plugin, and get readable Lua for the
[RS3 Client Plugin API](https://developer.runescape.com/rs/index.html).

Named after the Oneiromancer of Lunar Isle.

> **Status:** Oneiro has not had its first release yet. Plugins written in Java compile and build today, from a
> checkout of this repository (see [Building from source](#building-from-source)). Kotlin support and a guide from
> RuneLite to RS3 are planned.

## What it does

RS3 client plugins are Lua 5.4 scripts, which Jagex's moderators review before they are published on Mod.io. Oneiro
lets you write them in Java, with the tools you know: Gradle, your IDE, type checking and completion for the whole RS3
API. It compiles the Java into Lua that reads as if written by hand, one module per file, with comments carried over,
Lua Language Server annotations, and direct calls to the RS3 API. There is no runtime library. Only the Lua is
submitted and moderated.

The source keeps the shape of a RuneLite plugin, a `Plugin` class with `@Subscribe` methods and a settings interface,
and calls the RS3 API by the names Jagex documents:

```java
/** Logs the coins in your backpack whenever the amount changes. */
public final class CoinWatch extends Plugin {

    private CoinSettings settings;
    private int shown = -1;

    @Subscribe
    public void onInventoryUpdated(InventoryUpdatedEventPayload event) {
        // The bank and other inventories send updates too
        if (event.inv.id != id.Inv.RS3_INV || !settings.enabled()) {
            return;
        }
        int coins = Inventory.Find(config.Inv.RS3_INV).GetTotal(config.Obj.RS3_COINS);
        if (coins != shown) {
            shown = coins;
            log("Coins in the backpack: " + coins);
        }
    }
}
```

becomes `main.lua`:

```lua
-- Logs the coins in your backpack whenever the amount changes.

local CoinSettings = require("src/coin_settings")

local shown = -1

local function onInventoryUpdated(event)
    -- The bank and other inventories send updates too
    if event.inv.id ~= id.Inv.INV or not CoinSettings.enabled() then
        return
    end
    local coins = Inventory.Find(config.Inv.INV):GetTotal(config.Obj.COINS)
    if coins ~= shown then
        shown = coins
        log(formatStr("Coins in the backpack: {}", coins))
    end
end

Event.InventoryUpdated.Subscribe("coin-watch.onInventoryUpdated", onInventoryUpdated)

-- The client calls PluginShutdown when the plugin is disabled, and requires it to be global.
function PluginShutdown()
    Event.InventoryUpdated.Unsubscribe("coin-watch.onInventoryUpdated")
end
```

The Gradle plugin also writes `plugin.json` from the build, checks the folder that ships, runs the plugin's Lua tests,
zips it for upload, and links it into the beta client's `dev_plugins` folder. A construct Oneiro cannot
translate, or a call that needs a permission the build does not declare, is a compile error that says what to do
instead.

## Getting started

You need JDK 25 (Gradle can download it for you) and the RS3 beta client to try the plugin in game.

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Downloads a Java 25 toolchain on machines that have none installed
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "coin-watch"
```

`build.gradle.kts`:

```kotlin
plugins {
    id("org.adelheid.oneiro") version "0.1.0"
}

version = "1.0.0"

repositories {
    mavenCentral()
}

oneiro {
    stubs {
        // The RS3 API's type information, from which Oneiro generates the Java bindings on your machine
        remote("https://github.com/FriendlyDevelopment/runescape-api-stubs", "fe2272a5522944b9d3aa5015be51b9bca334630f")
    }
    plugin {
        name = "Coin Watch"
        contactEmail = "you@example.org"
    }
}
```

Put the plugin class under `src/main/java/`, then:

```sh
./gradlew build           # compiles, checks, tests and zips the plugin
./gradlew linkDevPlugin   # links it into the client's dev_plugins folder, once
```

In the client's console, `prf` finds the newly linked plugin and `pon coin-watch` enables it. After each build,
`prl coin-watch` reloads it.

## Documentation

- [The plugin model and the build](docs/plugin-model.md): the project layout, the `oneiro { }` block, the tasks and
  the checks.
- [Supported Java](docs/java-subset.md): every construct Oneiro accepts, the Lua it becomes, and what it rejects.
- [Architecture](docs/architecture.md): how Oneiro is put together, for contributors.
- The [RS3 Client Plugin API documentation](https://developer.runescape.com/rs/index.html) and Jagex's
  [plugin guidelines](https://developer.runescape.com/rs/submission/guidelines.html).

Jagex reviews every plugin before it is published and expects its developer to understand all of its code. Oneiro's
Lua is written to be read: read it before you submit.

## Building from source

```sh
./gradlew build
```

To build a plugin against a checkout before the first release, include it in the plugin's `settings.gradle.kts` and
apply the plugin without a version, as [CONTRIBUTING.md](CONTRIBUTING.md#trying-a-change-on-a-plugin) describes.

## Contributing

Contributions are welcome; [CONTRIBUTING.md](CONTRIBUTING.md) explains how to build, test and add to the supported
Java. Changes are listed in [CHANGELOG.md](CHANGELOG.md).

## Licence

MIT, see [LICENSE](LICENSE).

Oneiro is not affiliated with or endorsed by Jagex or RuneLite. RuneScape is a trademark of Jagex Ltd.
