# From RuneLite to RS3

For developers who have written RuneLite plugins for Old School and want to write an RS3 plugin with Oneiro. It maps
what a RuneLite plugin uses to its RS3 counterpart, or says why there is none, and ends with how the rules differ
between the games. RuneLite's names are those of its public API documentation; the RS3 API is documented by Jagex at
[developer.runescape.com](https://developer.runescape.com/rs/index.html). How to set up a project is in the
[README](../README.md#getting-started).

## What stays and what changes

The tools stay: Java or Kotlin, Gradle and your IDE, with completion and documentation for the whole RS3 API. So does
the plugin's shape: a class that extends `Plugin`, with `startUp()`, `shutDown()` and `@Subscribe` methods, and an
interface of settings annotated `@ConfigGroup`.

What changes:

- **The client runs Lua.** Oneiro compiles your Java or Kotlin to Lua, which is what you submit and what Jagex's
  moderators read. It accepts what has a readable Lua form, which is most of what plugins use;
  [java-subset.md](java-subset.md) and [kotlin-subset.md](kotlin-subset.md) list it.
- **There is no `Client` object and no injection.** The RS3 API is a set of global tables, which your code calls by
  name: `Player.GetEntity()`, `NPCList.All()`, `Inventory.Find(...)`.
- **The names are RS3's.** The API keeps the names Jagex documents, PascalCase methods included, and the IDs are RS3
  data.
- **Everything runs on the client's one thread**, with no Swing, no files, and no network beyond `HTTPRequest`.
- **Plugins are published on Mod.io** after Jagex moderates them, rather than through the Plugin Hub, and are tested on
  the beta world with the RS3 beta client.

## The plugin class

A RuneLite plugin, in brief:

```java
@PluginDescriptor(name = "Herb Tracker")
public class HerbPlugin extends Plugin {

    @Inject
    private Client client;

    @Inject
    private HerbConfig config;

    @Provides
    HerbConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(HerbConfig.class);
    }

    @Subscribe
    public void onGameTick(GameTick tick) {
        ...
    }
}
```

The same plugin with Oneiro; here and below, imports are left out, as the IDE adds them. In Java:

```java
public final class HerbPlugin extends Plugin {

    private HerbConfig settings;
    private final HerbTimer timer = new HerbTimer();

    @Override
    protected void startUp() {
        log("Herb Tracker started");
    }

    @Subscribe
    public void onServerLogic(ServerLogicEventPayload tick) {
        if (timer.tick()) {
            Alerts.notify("Your herbs are ready.");
        }
    }
}
```

In Kotlin:

```kotlin
class HerbPlugin : Plugin() {

    private lateinit var settings: HerbConfig
    private val timer = HerbTimer()

    override fun startUp() {
        log("Herb Tracker started")
    }

    @Subscribe
    fun onServerLogic(tick: ServerLogicEventPayload) {
        if (timer.tick()) {
            Alerts.notify("Your herbs are ready.")
        }
    }
}
```

| RuneLite | Oneiro |
| --- | --- |
| `@PluginDescriptor(name = ...)` | `oneiro { plugin { name = ...; contactEmail = ... } }` in `build.gradle.kts`, from which the build writes the manifest, `plugin.json` |
| `@Inject Client client` | Nothing: call the API's tables directly |
| `@Inject` the config, and `@Provides` it from `ConfigManager` | A field of the settings interface's type, which needs no value: in Kotlin, a `lateinit var` |
| `startUp()`, `shutDown()` | The same. `startUp()` runs when the client loads the plugin, and `shutDown()` when the plugin is disabled |
| `@Subscribe` on any object registered with the `EventBus` | `@Subscribe` on the plugin class's methods, or functions in Kotlin; elsewhere, `Event.ChatMessage.Subscribe(name, callback)` and `Unsubscribe(name)` |
| An event class, such as `GameTick` | Its payload class, such as `ServerLogicEventPayload`; the parameter's type names the event |
| `@PluginDependency` | Another plugin by its Mod.io ID: `dependencies { required(alias, modId) }` |

A plugin has one class that extends `Plugin`; the rest of its code is ordinary classes, each of which becomes a Lua
module. [plugin-model.md](plugin-model.md#the-plugin-class-events-and-settings) has the details.

One behaviour differs in a way that matters: **an exception in a callback ends its subscription.** RuneLite's
`EventBus` logs an exception and keeps calling the subscriber; the RS3 client logs the error and stops calling that
callback. Catch what may fail inside the callback, and when the console shows an error, `./gradlew explainError`
shows the lines of your source it came from ([plugin-model.md](plugin-model.md#explaining-a-client-error)).

## Events

| RuneLite | RS3 | Notes |
| --- | --- | --- |
| `GameTick` | `ServerLogic` | About every 0.6 seconds, after the server's packets; it passes nothing. The usual place to poll the game. |
| `ClientTick` | `Logic` | 50 a second, as `ClientTick`'s 20 ms; `logicTick` counts them. |
| `BeforeRender` | `Draw` | Every frame, with `timeDeltaSecs`. Only for drawing; compute on `ServerLogic`. |
| `GameStateChanged` | `GameStateChanged` | `GameState.GAME` once logged in. |
| `ItemContainerChanged` | `InventoryUpdated` | With the slots that changed. `InventoryAdded` and `InventoryRemoved` say when an inventory, such as the bank, appears and goes. |
| `StatChanged` | `SkillTransmit` | Experience and level, old and new. |
| `VarbitChanged` | `VarTransmit` | Fires on every transmit of the variable, even when its value is unchanged. |
| `NpcSpawned`, `NpcDespawned` | `NPCEntityAdded`, `NPCEntityRemoved` | Not fired for NPCs already in view when the plugin starts: scan `NPCList.All()` in `startUp()`. Removal also covers death; there is no `ActorDeath`. |
| `PlayerSpawned`, `PlayerDespawned` | `PlayerEntityAdded`, `PlayerEntityRemoved` | Information about other players draws moderation's closer review. |
| `ItemSpawned`, `ItemDespawned` | `ObjStackEntityAdded`, `ObjStackEntityChanged`, `ObjStackEntityRemoved` | Ground items, as stacks on a tile. |
| `GameObjectSpawned`, `GameObjectDespawned` | `LocAdded`, `LocRemoved` | Scenery is a *loc* in RS3. |
| `ChatMessage` | `ChatMessage` | Needs the `chatAccess` permission. |
| `MenuEntryAdded`, `MenuOpened` | `MiniMenuEntryAdded`, `MiniMenuOpened` | The right-click menu is the *mini menu*. |
| `PostMenuSort` | `MiniMenuReady` | The one place to reorder entries, with `MiniMenu.Swap`. |
| `MenuOptionClicked` | `MiniMenuEntrySelected` | Read-only: a plugin cannot consume the click. |
| `FocusChanged`, `CanvasSizeChanged` | `WindowFocusChanged`, `WindowSizeChanged` | |
| `ConfigChanged` | None | The plugin builds its own settings page and acts when its controls change; see [Settings](#settings). |
| `HitsplatApplied`, `AnimationChanged`, `InteractingChanged` | None | The RS3 API has no such events. |

The RS3 client also has events RuneLite has no counterpart for, such as `EntityDraw`, whose callback returns whether
to draw each NPC, player or ground item, and `RunEnergyTransmit`. Jagex's
[events page](https://developer.runescape.com/rs/core/events.html) lists them all.

## Reading the game

RuneLite's getters on `Client` become the RS3 tables. Each table that can be unavailable, such as `Player` on the
login screen or during a world switch, has `IsAvailable()`, and events can fire while it is unavailable. In Java:

```java
if (!Player.IsAvailable()) {
    return;
}
int farming = Player.GetLevelGameplay(SkillType.FARMING);
log("Farming level", farming, "at", Player.GetEntity().coordGrid.x, Player.GetEntity().coordGrid.z);
```

In Kotlin:

```kotlin
if (!Player.IsAvailable()) {
    return
}
val farming = Player.GetLevelGameplay(SkillType.FARMING)
log("Farming level", farming, "at", Player.GetEntity().coordGrid.x, Player.GetEntity().coordGrid.z)
```

| RuneLite | RS3 |
| --- | --- |
| `client.getLocalPlayer()`, null when logged out | `Player.IsAvailable()`, then `Player.GetEntity()` |
| `client.getBoostedSkillLevel(Skill.X)` | `Player.GetLevelGameplay(SkillType.X)`, with boosts and drains |
| `client.getRealSkillLevel(Skill.X)` | `Player.GetLevelUncapped(SkillType.X)`; `GetLevelCapped` applies the free-world caps |
| `client.getSkillExperience(Skill.X)` | `Player.GetXPUncapped(SkillType.X)` |
| `client.getItemContainer(...)` | `Inventory.Find(config.Inv.RS3_INV)`, then `GetTotal`, `GetSlot` and `GetSlots` |
| `client.getTopLevelWorldView().npcs()` | `NPCList.All()`, `ByDistance`, `ByName`, `ByConfig` and `Filter` |
| Other players | `PlayerList.All()`, `ByName` and `Filter`; `PlayerList.Self()` is the local player |
| `client.getVarbitValue(...)`, `client.getVarpValue(...)` | `vars.VarPlayer.RS3_<NAME>.value`, and `vars.VarClient` for the client's own |
| `WorldPoint` | `CoordGrid`: a level and a tile's `x` and `z` |
| `LocalPoint` | `CoordFine`, in fine units, 512 to a tile |
| `Perspective.localToCanvas(...)` | `ScreenConvert.Vector3ToScreen(...)`, and `ScreenToCoordGrid` the other way |

The lists take a filter, which sets only the tests it needs. In Java:

```java
NPCListFilterSettings nearby = new NPCListFilterSettings();
nearby.config = config.NPC.RS3_RAT;
nearby.coordGrid = Player.GetEntity().coordGrid;
nearby.range = 8;
List<NPCEntity> rats = NPCList.Filter(nearby);
```

In Kotlin, where `apply` would read `config` as the filter's own property rather than the `config` table:

```kotlin
val nearby = NPCListFilterSettings()
nearby.config = config.NPC.RS3_RAT
nearby.coordGrid = Player.GetEntity().coordGrid
nearby.range = 8
val rats = NPCList.Filter(nearby)
```

An entity is valid only while it is in view, as a RuneLite `NPC` is. Check its `isValid` before reading it on a later
tick, and keep its `uid` rather than the entity, to find it again with `NPCList.ByUID`. Each read hands out a new
object for the same entity, so Oneiro rejects an entity or a config as a map key; key by its `uid` or `id` instead.

## IDs

RuneLite's ID constants, such as `ItemID` and `NpcID`, become two tables per kind of thing: `id.Obj` holds the
numbers, and `config.Obj` the configs, which carry the name, the options and the rest of the static data. Items are
*objs*, NPCs *NPCs*, scenery *locs*, and inventories *invs*.

- **The IDs are RS3's.** Some numbers match Old School's, most do not; never copy one across.
- **The names are those of the game's cache**, which are not always those the game shows: a grimy guam is
  `UNIDENTIFIED_GUAM`. rs3-api-types, the stubs Oneiro generates the bindings from, lists every name with its number
  in its `generated/constants/` folder, which is the quickest place to search.
- **Every name has an `RS3_` prefix in Java and Kotlin**, so that names starting with a digit are names in both:
  `config.Obj.RS3_COINS` in the source is `config.Obj.COINS` in the Lua.
- A few names cannot be names in either language at all, such as one with a hyphen. The bindings leave them out with
  a warning, and `config.Obj.FromID(id)` looks one up by its number.

## Settings

A settings interface keeps its RuneLite shape. Oneiro stores each item in the client's `PersistentDB` under
`"<group>.<keyName>"`, and a setter with the same `keyName` writes it. In Java:

```java
@ConfigGroup("herbs")
public interface HerbConfig {

    @ConfigItem(keyName = "remind", name = "Remind me when herbs are ready")
    default boolean remind() {
        return true;
    }

    @ConfigItem(keyName = "remind", name = "Remind me when herbs are ready")
    void setRemind(boolean remind);
}
```

In Kotlin:

```kotlin
@ConfigGroup("herbs")
interface HerbConfig {

    @ConfigItem(keyName = "remind", name = "Remind me when herbs are ready")
    fun remind(): Boolean = true

    @ConfigItem(keyName = "remind", name = "Remind me when herbs are ready")
    fun setRemind(remind: Boolean)
}
```

An item is a `boolean`, an `int` or `long`, a `double`, a `String`, or an enum of the plugin's; in Kotlin, a `Boolean`,
an `Int` or `Long`, a `Double`, a `String`, or an enum class of the plugin's.

The difference is the page. RuneLite builds a settings panel from the interface; the RS3 client gives the plugin an
empty layer each time the player opens its settings, and the plugin builds the page itself with the `ui` components.
Set `settingsIcon = true` in the build, and subscribe to `SettingsLayerReady`. In Java:

```java
@Subscribe
public void onSettingsLayerReady(SettingsLayerReadyEventPayload event) {
    ui.Check reminder = new ui.Check((ui.ComponentCollection) event.component);
    reminder.stylesheetID = id.StyleSheet.RS3_CHECKBOX_DEFAULT;
    reminder.SetPos(10, 10);
    reminder.SetSize(240, 24);
    reminder.label = "Remind me when herbs are ready";
    reminder.value = settings.remind();
    reminder.Subscribe(ui.Hook.ONBUTTONPRESSED, (_, _, _) -> settings.setRemind(reminder.value));
}
```

In Kotlin, where the hook's callback is a trailing lambda:

```kotlin
@Subscribe
fun onSettingsLayerReady(event: SettingsLayerReadyEventPayload) {
    val reminder = ui.Check(event.component as ui.ComponentCollection).apply {
        stylesheetID = id.StyleSheet.RS3_CHECKBOX_DEFAULT
        SetPos(10, 10)
        SetSize(240, 24)
        label = "Remind me when herbs are ready"
        value = settings.remind()
    }
    reminder.Subscribe(ui.Hook.ONBUTTONPRESSED) { _, _, _ -> settings.setRemind(reminder.value) }
}
```

There is no `ConfigChanged`: the control's callback calls the setter, and applies the change itself. An item the page
does not show is stored all the same, which makes the interface the place for state kept between sessions, as hidden
items are in RuneLite.

## Overlays and panels

RS3 plugins do not paint. Where a RuneLite `Overlay` draws on a `Graphics2D` every frame, an RS3 plugin creates `ui`
components once, on a layer, and changes their properties when there is something new to show. For drawing in the
game world, it registers shapes, which the client draws.

| RuneLite | RS3 |
| --- | --- |
| `Overlay`, `OverlayPanel` with a `PanelComponent` | A `ui.GroupBox` with `ui.Text`, `ui.Sprite` and other components, on the plugin layer |
| `InfoBox`, `Counter`, `Timer` | No info box bar: a small box of the plugin's own, like the one below |
| `PluginPanel` behind a `NavigationButton` | The content layer behind the plugin's icon in the Plugins ribbon: `ribbonIcon = true`, and `ContentLayerReady`, which hands out an empty layer each time it opens |
| A panel built from Swing components: `JPanel`, `JButton`, `JLabel`, `JCheckBox`, layouts | [PrettyUI](https://prettyui.j3.gg/), a library plugin of views, windows, buttons, tabs, fields and dialogs in the game's look, with a layout that places each component below or beside the one before: a dependency, which [using-lua.md](using-lua.md#writing-a-definition-file) shows in use. Or the `ui` components themselves, placed by hand |
| Tile markers, NPC highlights, `OverlayUtil.renderPolygon` | Shapes in the world: `ShapeData`, drawn by a `ShapeEntity` (a fixed marker), a `ShapeInstance` (one that moves), or `ShapeList.Draw` in `Draw` or `EntityDraw` (for one frame) |
| Hiding NPCs or players | `EntityDraw`, whose callback returns `false` to hide one |
| `RuneLiteObject` with a model from `client.loadModel` | No counterpart: nothing puts a model into the world. Shapes draw geometry of the plugin's own; a real model can be a `ui.Model` laid over the scene, as described below |

A line of text at the top of the game window, which clicks pass through. In Java:

```java
public class StatusBox {

    private final ui.GroupBox box;
    private final ui.Text text;

    private StatusBox(ui.ComponentCollection layer) {
        box = new ui.GroupBox(layer);
        box.stylesheetID = id.StyleSheet.RS3_DEFAULT;
        box.SetHeaderLayout(0, 0, 0, 0, ui.GroupBoxHeaderAlignMode.LEFT);
        box.SetSize(200, 32);
        // Centred across the top, 8 units down
        box.SetPos(0, 8, 0.5, 0.0, -0.5, 0.0);
        box.clickthrough = true;

        text = new ui.Text(box);
        text.font = id.Font.RS3_MUSEO_SANS_15PT_REGULAR;
        text.SetSize(0, 0, 1.0, 1.0);
        text.AlignTo(ui.AlignMode.CENTRE);
        text.clickthrough = true;
    }

    /** A box on the plugin layer, or null when the client has no such layer. */
    public static @Nullable StatusBox onPluginLayer() {
        ui.@Nullable Component layer = ui.Interfaces.GetComponent(id.Component.RS3_TOPLEVEL_V2__PLUGIN_BUILD_LAYER_TOP);
        return layer == null ? null : new StatusBox((ui.ComponentCollection) layer);
    }

    public void show(String line) {
        text.content = line;
    }

    public void destroy() {
        box.Destroy();
    }
}
```

In Kotlin:

```kotlin
class StatusBox private constructor(layer: ui.ComponentCollection) {

    private val box = ui.GroupBox(layer).apply {
        stylesheetID = id.StyleSheet.RS3_DEFAULT
        SetHeaderLayout(0, 0, 0, 0, ui.GroupBoxHeaderAlignMode.LEFT)
        SetSize(200, 32)
        // Centred across the top, 8 units down
        SetPos(0, 8, 0.5, 0.0, -0.5, 0.0)
        clickthrough = true
    }

    private val text = ui.Text(box).apply {
        font = id.Font.RS3_MUSEO_SANS_15PT_REGULAR
        SetSize(0, 0, 1.0, 1.0)
        AlignTo(ui.AlignMode.CENTRE)
        clickthrough = true
    }

    fun show(line: String) {
        text.content = line
    }

    fun destroy() {
        box.Destroy()
    }

    companion object {

        /** A box on the plugin layer, or null when the client has no such layer. */
        fun onPluginLayer(): StatusBox? {
            val layer = ui.Interfaces.GetComponent(id.Component.RS3_TOPLEVEL_V2__PLUGIN_BUILD_LAYER_TOP)
            return layer?.let { StatusBox(it as ui.ComponentCollection) }
        }
    }
}
```

A component without a size is invisible, as is a `GroupBox` without a style sheet and a `Text` without a font. Create
the components in `startUp()`, and destroy them in `shutDown()`.

An outline on the ground around a tile, which the client draws until the plugin removes it. In Java:

```java
ShapeData square = new ShapeData();
// A tile is 512 fine units across, so these corners sit just inside it
square.positions = List.of(new Vector3(-250, 0, -250), new Vector3(250, 0, -250), new Vector3(250, 0, 250),
        new Vector3(-250, 0, 250));
square.lines = List.of(0, 1, 1, 2, 2, 3, 3, 0);
ShapeData.Register("herbs.square", square);
ShapeEntity marker = ShapeList.CreateEntity("herbs.patch");
if (marker != null) {
    marker.SetShapeDataName("herbs.square");
    marker.coordGrid = tile;
    marker.colour = 0x40FF40FFL;
}
```

In Kotlin, where a `Vector3`'s coordinates are written as decimals, since Kotlin turns no `Int` into a `Double`:

```kotlin
val square = ShapeData().apply {
    // A tile is 512 fine units across, so these corners sit just inside it
    positions = listOf(Vector3(-250.0, 0.0, -250.0), Vector3(250.0, 0.0, -250.0), Vector3(250.0, 0.0, 250.0),
        Vector3(-250.0, 0.0, 250.0))
    lines = listOf(0, 1, 1, 2, 2, 3, 3, 0)
}
ShapeData.Register("herbs.square", square)
ShapeList.CreateEntity("herbs.patch")?.apply {
    SetShapeDataName("herbs.square")
    coordGrid = tile
    colour = 0x40FF40FFL
}
```

RS3 has no `RuneLiteObject`: the API puts no model into the scene, and an NPC, player or loc has no member that
changes how it looks. Geometry of the plugin's own is a shape, as above. A real model can be shown by a `ui.Model` on
the plugin layer, moved in `Draw` to the place on screen that `ScreenConvert.Vector3ToScreen` gives for its spot in the
world. Being a component, it is drawn above everything, the player and NPCs included. In the client, `SetTransform`
does not turn a lone model; to turn it, show it as an NPC whose `ui.NPCCustomisation` replaces the NPC's models with
it, and set the rotation of the customisation's model slot. The Javadoc of `ui.Model` and `SetModelSlot` has what the
client was found to do.

Jagex's [UI pages](https://developer.runescape.com/rs/ui/index.html) describe the components, and the
[ShapeList page](https://developer.runescape.com/rs/game/list/shapelist.html) the shapes.

## Notifiers

RuneLite's `Notifier` reaches outside the game: a desktop notification, a flashing window, a sound. An RS3 plugin
stays inside the client, and works the same on Windows, Linux, Android and iOS. It can tell the player in three ways:
a line in the chat, a sound, and something on screen. In Java:

```java
public final class Alerts {

    private Alerts() {
    }

    @SuppressWarnings("oneiro:chat")
    public static void notify(String message) {
        if (CurrentPlugin.HasPermission(PluginPermissions.chatAccess)) {
            Chat.AddMessage(message);
        }
        audio.Play(Resources.sounds.ready);
    }
}
```

In Kotlin:

```kotlin
object Alerts {

    @Suppress("oneiro:chat")
    fun notify(message: String) {
        if (CurrentPlugin.HasPermission(PluginPermissions.chatAccess)) {
            Chat.AddMessage(message)
        }
        audio.Play(Resources.sounds.ready)
    }
}
```

- **The chat** takes the place of `ChatMessageManager` and `client.addChatMessage`. `Chat.AddMessage` writes a line
  only the player sees, and needs the `chatAccess` permission: declare it as optional in the build, and check
  `CurrentPlugin.HasPermission` before writing, since the player may decline it. Oneiro warns where a plugin uses the
  chat, naming what moderation looks at; the `@SuppressWarnings` above, `@Suppress` in Kotlin, says the plugin has
  met it.
- **A sound** is an Ogg Vorbis file under `src/main/resources/`, which the build ships and the generated `Resources`
  class names: `src/main/resources/sounds/ready.ogg` is `Resources.sounds.ready`. Custom sounds draw moderation's
  closer review.
- **On screen**, a component like the status box above, shown and hidden.

## Timers

RuneLite plugins time things with `Instant.now()`, `@Schedule` and executors. The RS3 client has none of these: it
runs plugins on its one thread and gives them no clock. Time is counted in ticks, `ServerLogic`'s, about every 0.6
seconds, or `Logic`'s, 50 a second. Oneiro rejects reading the clock, as `System.currentTimeMillis()`, `Instant.now()`
and Kotlin's `measureTimeMillis { }` do, and threads, executors and Kotlin's `thread { }`, with an error that says so.
In Java:

```java
/** Counts down a herb's growth in game ticks, which come about every 0.6 seconds. */
public class HerbTimer {

    private int ticksLeft;

    public void start(int minutes) {
        ticksLeft = minutes * 100;
    }

    /** Counts one tick; true on the tick the herbs are ready. */
    public boolean tick() {
        if (ticksLeft == 0) {
            return false;
        }
        ticksLeft--;
        return ticksLeft == 0;
    }

    /** The time left as minutes and seconds. */
    public String text() {
        int seconds = ticksLeft * 6 / 10;
        int rest = seconds % 60;
        return seconds / 60 + ":" + (rest < 10 ? "0" + rest : String.valueOf(rest));
    }
}
```

In Kotlin:

```kotlin
/** Counts down a herb's growth in game ticks, which come about every 0.6 seconds. */
class HerbTimer {

    private var ticksLeft = 0

    fun start(minutes: Int) {
        ticksLeft = minutes * 100
    }

    /** Counts one tick; true on the tick the herbs are ready. */
    fun tick(): Boolean {
        if (ticksLeft == 0) {
            return false
        }
        ticksLeft--
        return ticksLeft == 0
    }

    /** The time left as minutes and seconds. */
    fun text(): String {
        val seconds = ticksLeft * 6 / 10
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }
}
```

The plugin calls `timer.tick()` from `onServerLogic`, as in [The plugin class](#the-plugin-class), and shows
`timer.text()` in a box. A RuneLite `Timer` info box becomes this class and a box of the plugin's own. Some payloads
carry a time of their own, such as `ChatMessage`'s `timestamp`.

## Trackers

A tracker counts from events and keeps its totals between sessions in the settings interface. This one counts the
grimy guam the player picks. In Java:

```java
@Subscribe
public void onInventoryUpdated(InventoryUpdatedEventPayload event) {
    // The bank and other inventories send updates too
    if (event.inv.id != id.Inv.RS3_INV) {
        return;
    }
    int now = herbsHeld();
    if (now > held) {
        settings.setPicked(settings.picked() + now - held);
    }
    held = now;
}

private static int herbsHeld() {
    return Inventory.Find(config.Inv.RS3_INV).GetTotal(config.Obj.RS3_UNIDENTIFIED_GUAM);
}
```

In Kotlin:

```kotlin
@Subscribe
fun onInventoryUpdated(event: InventoryUpdatedEventPayload) {
    // The bank and other inventories send updates too
    if (event.inv.id != id.Inv.RS3_INV) {
        return
    }
    val now = herbsHeld()
    if (now > held) {
        settings.setPicked(settings.picked() + now - held)
    }
    held = now
}

private fun herbsHeld(): Int = Inventory.Find(config.Inv.RS3_INV).GetTotal(config.Obj.RS3_UNIDENTIFIED_GUAM)
```

with `held = herbsHeld()` in `startUp()`, and a `picked` item and its setter in `HerbConfig`. Each setter call is a
write to disk, so a plugin writes when a value changes, never on every frame. Experience trackers listen to
`SkillTransmit`, loot trackers to `InventoryUpdated` and the `ObjStackEntity` events.

A RuneLite plugin may write a log file or post to a website. An RS3 plugin has no files, and reaches the internet only
through `HTTPRequest`, which needs the `httpAccess` permission and a base URL the build declares:
`urls { url("prices", "https://prices.example.org") }`, read in code as `Urls.prices`. As with the chat, the player
may decline the permission. The request calls back on the client's thread. In Java:

```java
if (!CurrentPlugin.HasPermission(PluginPermissions.httpAccess)) {
    return;
}
HTTPRequest request = new HTTPRequest(Urls.prices, "/item/" + itemId);
request.Get(done -> {
    if (done.successful) {
        log("Price:", done.data);
    } else {
        log("Price lookup failed:", done.responseCode);
    }
});
```

In Kotlin:

```kotlin
if (!CurrentPlugin.HasPermission(PluginPermissions.httpAccess)) {
    return
}
val request = HTTPRequest(Urls.prices, "/item/$itemId")
request.Get { done ->
    if (done.successful) {
        log("Price:", done.data)
    } else {
        log("Price lookup failed:", done.responseCode)
    }
}
```

It replaces `OkHttpClient`, with `GET` and `POST` only. The client has no JSON library, so keep what the server sends
simple to read.

## The development loop

| RuneLite | RS3 with Oneiro |
| --- | --- |
| Run the client from the IDE, with the plugin loaded | `./gradlew build`, then `./gradlew linkDevPlugin` once; the beta client loads the plugin from its `dev_plugins` folder |
| Restart the client to try a change | `prl <id>` in the client's console after each build |
| A stack trace in the IDE | An error in the client's console, against the Lua; `./gradlew explainError --input=console.txt` shows the lines of your source, and what the client's own messages mean |
| Test on a live world | Test on the beta world |
| Code only for development | `src/dev/java` or `src/dev/kotlin`, such as cheats with `ExecuteConsoleCommand`; it never ships |

## How the rules differ

RuneLite plugins follow Jagex's third-party client guidelines for Old School. RS3 plugins are moderated by Jagex under
its own [plugin guidelines](https://developer.runescape.com/rs/submission/guidelines.html), which are stricter in
places that RuneLite plugins often reach. The guidelines say they are not exhaustive and change over time; the page is the
authority, and moderators judge what a feature does, not which calls it makes.

| A feature RuneLite plugins often have | Under the RS3 guidelines |
| --- | --- |
| Trackers, calculators, loot and session logs, manually placed markers | Allowed |
| Notifications of visible events outside combat | Allowed |
| Enemy timers and state, combat overlays | Reviewed, and only up to parity: recreating for other content a visual the game already shows for some |
| Swapping menu entries, other input customisation | Reviewed, and prohibited where it reduces the game's intended difficulty |
| Menu entries that act on the server | Prohibited; the API has no way to make one |
| Prayer-switching or ability indicators | Prohibited |
| Predicting attacks or safe spots, marking where to stand | Prohibited beyond parity; markers the player places are allowed |
| Showing which player an NPC targets; scouting, targeting or tracking players in PvP | Prohibited |
| Looking up, recording or sending information about other players | Obtaining it is reviewed; exporting it is prohibited |
| Recolouring player models | Prohibited; hiding them entirely is allowed |
| Moving or resizing click zones, unhiding hidden interface parts | Prohibited |
| Custom images and sounds | Reviewed |
| Sending data to a website | Reviewed, as is any plugin that requests a permission |

Oneiro warns at the uses moderation reviews closely: the chat, HTTP requests, the tracking ID, changes to the mini
menu, and finding other players. Each warning names what moderation looks at, and
[plugin-model.md](plugin-model.md#compiling) lists them.

Two more differences shape the submission:

- **Moderators read the code.** Jagex rejects code that is hard to follow, and names transcoded output among it. Oneiro
  writes Lua that reads as written by hand, with your comments carried over; read it before you submit, because you
  are expected to understand all of it.
- **The Mod.io description has required sections:** a contact address, a statement acknowledging Jagex's EULA, a
  paragraph per permission, and how the plugin treats data. Jagex's
  [example description](https://developer.runescape.com/rs/submission/example.html) gives the wording.
