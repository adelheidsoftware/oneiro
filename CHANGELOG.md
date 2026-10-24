# Changelog

All notable changes to Oneiro are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

[0.1.0]: https://github.com/adelheidsoftware/oneiro/releases/tag/v0.1.0
