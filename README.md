# Oneiro

Write RuneScape 3 plugins in Java or Kotlin, the way you would write a RuneLite plugin, and get readable Lua for the
RS3 Client Plugin API.

Named after the Oneiromancer of Lunar Isle.

> **Status:** early development. A plugin written in Java, with its events, settings, resources and calls into the RS3
> API, compiles to house-style Lua ([what is supported](docs/java-subset.md)), and the Gradle plugin builds, checks,
> tests, zips and links it ([the build](docs/plugin-model.md)). Oneiro is not published yet, so a plugin's build
> includes a checkout of this repository (`includeBuild`). Kotlin support and a guide from RuneLite to RS3 are planned.

## What it does

RS3 client plugins are Lua 5.4 scripts that Jagex moderators review before they are published on Mod.io. Oneiro
compiles a plugin written in Java or Kotlin into Lua that reads as if written by hand: one module per file, comments
carried over, Lua Language Server annotations, and direct calls to the RS3 API. The source keeps the shape
of a RuneLite plugin (a `Plugin` class, `startUp` and `shutDown`, `@Subscribe` methods, config interfaces) and calls
the RS3 API by the names Jagex documents; Oneiro translates it when it compiles. Only the Lua is submitted and moderated.

## Building

Oneiro needs JDK 25; Gradle uses an installed one or downloads it.

```sh
./gradlew build
```

## Licence

MIT, see [LICENSE](LICENSE).

Oneiro is not affiliated with or endorsed by Jagex or RuneLite. RuneScape is a trademark of Jagex Ltd.
