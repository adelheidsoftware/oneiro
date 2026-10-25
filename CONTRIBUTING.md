# Contributing to Oneiro

Thank you for helping. This page covers building Oneiro, running its tests, adding to the Java and Kotlin it supports,
and the code style. [docs/architecture.md](docs/architecture.md) explains how the compiler is put together.

For anything larger than a small fix, please open an issue first, so that we can agree on the approach before you
spend time on it.

## Building

Oneiro needs JDK 25. Gradle uses an installed JDK 25, or downloads one through the Foojay toolchain resolver.

```sh
./gradlew build
```

This compiles every module with Error Prone and NullAway, runs Checkstyle, the unit tests, the conformance suite and
the Gradle plugin's functional test. To run one module's tests, name it: `./gradlew :oneiro-lua-tree:test`.

The build needs nothing else installed. A few checks use tools or repositories when they are there and are skipped
otherwise:

| What | How to enable it | What it adds |
| --- | --- | --- |
| [StyLua](https://github.com/JohnnyMorganz/StyLua) 2.6.0 | `oneiro.styluaExecutable=/path/to/stylua` in `~/.gradle/gradle.properties` | Checks that every expected Lua file is formatted as StyLua formats it, with the settings in `stylua.toml`. When the `CI` environment variable is set, a missing StyLua fails the suite instead. |
| An installed Lua 5.4 | `oneiro.luaExecutable=/path/to/lua` in `~/.gradle/gradle.properties` | Runs the conformance suite on that interpreter instead of the bundled Lua 5.4.8. |
| [runescape-api-stubs](https://github.com/FriendlyDevelopment/runescape-api-stubs) | A checkout next to Oneiro's, at `../runescape-api-stubs` | Generates the bindings from the real stubs, which must produce no warning. Otherwise the generator's tests use the small fixture in `oneiro-bindings-gen/src/test/stubs/`. |
| Blueprint-Helper | A checkout next to Oneiro's, at `../Blueprint-Helper` | Runs that plugin's own tests against the Lua Oneiro writes for five of its core modules, from the Java in `conformance/src/test/resources/blueprint-helper/`, and reads its modules' annotations, which must produce no warning. |
| [Zyklib](https://codeberg.org/ZyklopMarco/runescape-api-zyklib) | A checkout next to Oneiro's, at `../runescape-api-zyklib` | Reads the library's annotated code and, with the real stubs and Blueprint-Helper also there, generates its bindings and those of Blueprint-Helper's modules, which must produce no warning and compile. The fixtures in `oneiro-bindings-gen/src/test/libraries/` run either way. |

StyLua's layout can change between versions, so use 2.6.0 for the expected files.

## Trying a change on a plugin

A plugin's build can use a checkout of Oneiro instead of a release. In the plugin's `settings.gradle.kts`:

```kotlin
pluginManagement {
    includeBuild("../oneiro")
}

includeBuild("../oneiro")
```

The first `includeBuild` provides the Gradle plugin; the second provides the modules it adds to the plugin's build.
Each build of the plugin then uses Oneiro as it is in the checkout.

## Supporting more Java and Kotlin

Each construct Oneiro accepts, and each one it rejects, is a row in [docs/java-subset.md](docs/java-subset.md) or
[docs/kotlin-subset.md](docs/kotlin-subset.md) and a case in the conformance suite. A change to a supported language
comes with both.

A case is a folder under `conformance/src/test/resources/cases/`:

| File | Contents |
| --- | --- |
| `input/` | The Java sources, in the root package `plugin`. |
| `expected/` | The Lua Oneiro must write, at the paths it has in the plugin: `main.lua`, `src/...`, `dev/main.lua`. |
| `diagnostics.txt` | The exact errors and warnings the case must report, in javac's format; a case without one must report none. |
| `check.lua` | Optional. Assertions run on Lua 5.4 against the generated modules, to show that the Lua behaves as the source does. |
| `lua/` | Optional. Lua of the plugin's own, laid out as a plugin's `src/main/lua/` is, which runs beside the generated modules under `src/`; the case's sources call it through the bindings of its annotations. |
| `types/` | Optional. Definition files that give those bindings instead, where the Lua is another's, as Blueprint-Helper's is. |
| `case.properties` | Optional. The plugin's identifier (`id`), its `permissions` and manifest `flags`, and `dev=true` for a dev source set. |
| `kotlin/` | Optional. The same plugin in Kotlin, the case's twin, which must give the same Lua as `expected/`, and passes the same `check.lua`; with its own `diagnostics.txt` when it has any, and its own `expected/` for a module where idiomatic Kotlin reads differently. |

A case for what only Kotlin has, such as `kotlin-scopes`, has no `input/`: its sources are in `kotlin/` alone, beside
`expected/`, `check.lua` and, in `kotlin/`, any `diagnostics.txt`.

Write the expected Lua by hand, in the style described below, as a careful person would write it. Never copy it from
Oneiro's output: the case exists to check that output against what it should be.

## Code style

The build enforces the layout through Checkstyle and `.editorconfig`; the rest is for review.

- Java 25, except `oneiro-plugin-model` and `oneiro-gradle-plugin`, which are compiled for Java 17 because they run in
  the Gradle daemon.
- Tabs for indentation, and at most 120 columns, a tab counting as four.
- A Javadoc comment on every class saying what it does, and comments where the intent of the code is not obvious.
  Comments describe the code as it is; history belongs in the commit message.
- No fully qualified names in code: import the type or the static member. The one exception is a name that a nested
  class shadows in its own file, where Java leaves no other way.
- Compiler trees are sealed interfaces of records, and passes over them are exhaustive `switch` expressions.
- Every package is `@NullMarked` (JSpecify), and NullAway turns a nullness mistake into a compile error. Mark what can
  be null with `@Nullable`.
- An error message is a full sentence that says what is wrong and what to write instead.

## The Lua style

The Lua Oneiro writes, and the expected Lua in the conformance suite, follow the conventions of hand-written RS3
plugins:

- Lua 5.4, four spaces, 120 columns, formatted as StyLua 2.6.0 formats it with `stylua.toml`.
- A header comment at the top of every file, whose first line is the file's path inside the plugin, such as
  `-- src/core/row.lua`: the client's errors name a file only by its first line. Then a comment above each function
  that has documentation in the source, and Lua Language Server annotations (`---@param`, `---@return`) for the
  signature.
- One module table per file, returned at its end; `local` for everything else. The only globals are the ones the
  client requires, such as `PluginShutdown`, each with a comment saying so.
- `camelCase` for locals and functions, `PascalCase` for modules and classes, `UPPER_SNAKE_CASE` for constants.
- `formatStr` for building strings, and the RS3 API called by its documented names.
- No `goto` except the `continue` idiom, `goto continue` with a `::continue::` label at the end of the loop body.

## Pull requests

- Keep a pull request to one change, with tests, and the documentation it affects.
- Run `./gradlew build` before you open it; it must pass.
- Add a line to the `Unreleased` section of [CHANGELOG.md](CHANGELOG.md) for anything a plugin author would notice.

By contributing, you agree that your contributions are licensed under the [MIT Licence](LICENSE).
