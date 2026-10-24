# Architecture

How Oneiro is put together, for people who work on it. What Oneiro accepts and the Lua it writes are in
[java-subset.md](java-subset.md); how a plugin's build uses it is in [plugin-model.md](plugin-model.md).

## Principles

- **The Lua is the product.** Only the Lua is submitted to Mod.io, and Jagex's moderators read it. Every design choice
  is judged by the Lua it produces, which must read like a careful person wrote it: a header comment per file,
  starting with the file's path so that the client's errors name it, Lua Language Server annotations, one module
  table per file, and the RS3 API called by its documented names.
- **A compiler, not a runtime.** Each Java construct becomes the Lua a person would write for it, at compile time.
  There is no runtime library. The few translations that need more than one expression use small helper functions,
  which a module defines only when it uses them, each with a comment.
- **Idiomatic source first.** Where a faithful, readable translation exists, the ordinary Java form is accepted. Only
  genuine gaps between Java and Lua are rejected, with an error that says why and what to write instead.
- **Nothing hidden.** Oneiro downloads nothing the build does not name, never adds a permission to the manifest, and
  reports every comment it cannot place instead of dropping it.

## From source to Lua

```text
runescape-api-stubs ──► oneiroBindings ──► RS3 bindings (Java classes on the compile classpath)
                                                │
plugin's Java ──► javac ────────────────────────┴──► Oneiro javac plugin ──► Lua tree ──► printer ──► .lua files
                  (compileJava)                       (oneiro-frontend-java)  (oneiro-lua-tree)
```

1. **Bindings.** `oneiroBindings` runs the bindings generator in a worker process on JDK 25. It parses the LuaCATS
   files and name lists of runescape-api-stubs, applies the hand-kept `overrides.txt`, and writes one Java class per
   Lua table. The classes carry annotations that tell the compiler how Lua calls each member (`@LuaColon`, `@LuaDot`,
   `@LuaReturnValues` and the others in `oneiro-api`). The bindings are generated on each developer's machine;
   nothing derived from the stubs is published.
2. **Compiling.** A plugin's own `compileJava` runs javac with Oneiro as a javac plugin. Once javac has analysed every
   class without errors, Oneiro lowers each source file into a Lua module. A construct it cannot translate becomes an
   ordinary javac error, so it shows in the IDE and the build log like a type error.
3. **Printing.** The printer turns each module's tree into text in the house style, at 120 columns, and records the
   Java line each Lua line came from. The compiler writes those line maps into the build, where `explainError` reads
   them to show a client error in Java terms.
4. **Packaging.** The Gradle plugin writes `plugin.json` from the build's `oneiro { plugin { } }` block, assembles
   the folder that ships, checks it, zips it, and runs the plugin's Lua tests.

## Modules

| Module | Published | What it holds |
| --- | --- | --- |
| `oneiro-api` | Yes | What plugin authors compile against: `Plugin`, `@Subscribe`, `@ConfigGroup`, the annotations that describe existing Lua, and the dedicated Lua APIs. |
| `oneiro-lua-tree` | Yes | The Lua tree (`Chunk`, `Statement`, `Expression`), the printer and the line map. It knows nothing about Java. |
| `oneiro-lua-runner` | Yes | Runs Lua 5.4: the bundled Lua 5.4.8 through LuaJava, or an installed interpreter, and the plugin's Lua tests. |
| `oneiro-bindings-gen` | Yes | Generates the RS3 bindings from the stubs and `overrides.txt`. |
| `oneiro-plugin-model` | Yes | The plugin apart from its code: the manifest, the checks of the folder that ships, the typed `Resources` and `Urls` classes, the compiler's settings, and the line maps and console errors behind `explainError`. |
| `oneiro-lowering` | Yes | What lowering is apart from the source language, shared by both front ends: the compile result and its diagnostics, Lua modules, the plugin facts the code is checked against, module paths, local names, comment text, doc annotations and index arithmetic. |
| `oneiro-frontend-java` | Yes | The Java front end: javac's trees to the Lua tree, and the javac plugin. |
| `oneiro-frontend-kotlin` | Yes | The Kotlin front end, in Kotlin: the Kotlin Analysis API's trees to the Lua tree. In progress; the Gradle plugin does not use it yet. |
| `oneiro-gradle-plugin` | Yes | The Gradle plugin `org.adelheid.oneiro`: the `oneiro { }` block and the tasks. |
| `conformance` | No | The conformance suite: Java inputs and their Kotlin twins, with hand-written expected Lua. |

```text
oneiro-gradle-plugin   ──► oneiro-plugin-model
oneiro-frontend-java   ──► oneiro-lowering, oneiro-plugin-model, (oneiro-api at compile time)
oneiro-frontend-kotlin ──► oneiro-lowering, the Kotlin Analysis API and the IntelliJ platform
oneiro-lowering        ──► oneiro-lua-tree
oneiro-bindings-gen    ──► oneiro-api
```

`oneiro-gradle-plugin` and `oneiro-plugin-model` are compiled for Java 17, because they run inside the Gradle daemon,
which may use an older JDK than Oneiro's. Everything else needs JDK 25. The Gradle plugin reaches the JDK 25 code
without linking against it: the compiler runs inside the plugin's javac, and the bindings generator and the Lua tests
run in worker processes on JDK 25, called by name (`Reflection`).

## The Java front end

The front end lives in `org.adelheid.oneiro.java`. `JavaFrontend` compiles a set of sources on its own (the conformance
suite uses it), and `OneiroJavacPlugin` does the same inside a plugin's `compileJava`. Both hand each analysed source
file to the classes below.

- **`UnitLowering`** turns one source file into one Lua module: the header from the class Javadoc, a `require` line
  for each plugin class it uses, the helpers its translations need, the module table, and its members. The parts of
  a file each have a class: `Constructors`, `Overloading` (default chains of overloads), `MemberComments` (Javadoc to
  `---@param` and `---@return`), `InheritedMembers`, `PluginEntry` (the plugin class as `main.lua`), `SettingsModule`
  (a `@ConfigGroup` interface over `PersistentDB`), `DeclarationChecks`, `NonNegativeMembers`, `ReviewWarnings`
  (the warnings at uses moderation reviews closely) and `ModuleAssembly`, which puts the module's statements in the
  order Lua needs them.
- **`BodyLowering`** lowers the statements and expressions of one method. It keeps the scope that the rest share and
  hands each kind of code to a class of its own: `Loops` and `OneBasedLoops`, `Switches`, `Exceptions` (`try` as
  `pcall`), `Lambdas` and `MethodReferences`, `Operators`, `TypeTests`, `ObjectCreation` and `PluginCalls`.
- **Calls outside the plugin.** `ApiCalls` handles the RS3 bindings, reading their annotations. `LibraryCalls` handles
  the Java library, with `StringMethods`, `NumberMethods` and `CollectionMethods`; `StringBuilding` turns
  concatenation and `String.format` into `formatStr`; `StreamLoops` fuses a stream pipeline into one loop; and
  `Comparators` turns a comparator into the test `table.sort` takes.
- **Shared knowledge.** `JavaTypes` answers questions about types, `SourceFacts` about the source (which integers are
  never negative, what code assigns), `CommentIndex` recovers the comments javac does not keep, and `Helper` defines
  the Lua helper functions.
- **Rejections.** When a construct has no translation, lowering throws a `Rejection`. The member being lowered is
  skipped and the rejection becomes an error at its position, so one run reports every problem. No Lua is written
  while any error remains.

## The Kotlin front end

The Kotlin front end lives in `org.adelheid.oneiro.kotlin` and is written in Kotlin, the language the Kotlin
Analysis API is made for. It is in progress: [kotlin-subset.md](kotlin-subset.md) lists what it accepts so far, and
the Gradle plugin does not use it yet.

- **`KotlinFrontend`** compiles a set of sources: `AnalysisSessions` builds a standalone Analysis API session over
  them and their classpath, Kotlin's own errors are reported first, and each file is then lowered and the session
  disposed of. The Analysis API is released with Kotlin and needs the IntelliJ platform build that Kotlin release
  was built against; the version catalog pins the pair.
- **`FileLowering`** turns one file into one Lua module, and keeps what the file shares: its diagnostics, its
  `SourceText` (positions, and the comments, which Kotlin's tree keeps), `KotlinTypes`, and the loops that
  `OneBasedLoops` lets count from 1. **`BodyLowering`** lowers the statements and expressions of a function.
- **Rejections** work as in the Java front end: a `Rejection` skips the member being lowered and becomes an error
  at its position.

What lowering is apart from the language, such as module paths, local names and the folding of index arithmetic,
lives in `oneiro-lowering`, where both front ends use it; more moves there as the Kotlin front end needs it.

## The bindings generator

The generator lives in `org.adelheid.oneiro.bindings`, and `BindingsGenerator` runs it.

1. `Stubs` reads a checkout. `LuaCatsParser` and `TypeParser` turn the LuaCATS files into an `ApiModel`, and
   `CsvConstants` reads the name lists behind `id`, `config` and `var`.
2. `Overrides` reads `overrides.txt`, which states what the stubs do not: which tables are singletons, which values
   can be `nil`, which fields are read-only or hand out copies, which members need a permission or draw moderation's
   closer review, and the Java names for Lua names Java cannot use. The file's own header describes its format. `ModelCorrections` applies its corrections to the model.
3. `JavaBindings` writes the sources, with `MemberBindings` for fields and methods, `TypeMapping` for types,
   `ConstantTables` for enums, hooks and constant namespaces, and `SourceFiles` for the files that do not come from a
   table.
4. The constant namespaces hold about half a million names; `config.Loc` alone has more than one class can hold.
   `ConstantChunks` writes them straight to class files with the JDK's ClassFile API, as a chain of chunk classes
   that each extend the one before, which is also far faster than compiling them from Java. `Jars` packs everything
   into reproducible jars.

## Tests

- **Conformance.** Each folder under `conformance/src/test/resources/cases/` is one case. `ConformanceTest` compiles
  its `input/` and compares the Lua with the hand-written `expected/` files, or the errors with `diagnostics.txt`.
  It then runs `check.lua` against the generated modules on Lua 5.4, and checks that the expected Lua is formatted as
  StyLua formats it. Every row of [java-subset.md](java-subset.md) has a case.
- **Unit tests** cover the printer and the line map, the bundled Lua, the bindings generator (on a small stubs
  fixture, and on a checkout of runescape-api-stubs when there is one), the manifest, the package checks and the
  typed sources.
- **The Gradle plugin's functional test** publishes the modules to a repository inside the build and builds a plugin
  against them with Gradle TestKit, as a plugin author would.

[CONTRIBUTING.md](../CONTRIBUTING.md) explains how to run them and how to add a case.
