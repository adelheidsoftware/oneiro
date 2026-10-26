---@meta

---The kinds of entity a payload names.
---@enum EntityType
EntityType = {
    LOCATION = 0,
    NPC = 1,
    PLAYER = 2,
}

---The name of one of the values, as `EntityType.FromID(EntityType.LOCATION)` is `"LOCATION"`.
---@param value integer
---@return string
---@diagnostic disable-next-line: inject-field
function EntityType.FromID(value) end

---The client's states.
---@enum GameState
GameState = {
    LOGINSCREEN = 10,
    LOBBY = 20,
    GAME = 30,
}

---The name of one of the values, as `GameState.FromID(GameState.LOGINSCREEN)` is `"LOGINSCREEN"`.
---@param value integer
---@return string
---@diagnostic disable-next-line: inject-field
function GameState.FromID(value) end

---Subscribe a callback under a name; unsubscribe it by the name.
---@class EventSubscription<T, RetT>
---@field Subscribe fun(name: string, callback: fun(event: T): RetT)
---@field Unsubscribe fun(name: string)

---Fifty times a second.
---@class LogicEventPayload
---@field logicTick integer (Read-only) Logic ticks since the client started.

---An inventory changed.
---@class InventoryUpdatedEventPayload
---@field inv InvType (Read-only) The inventory.
---@field slotsUpdated integer[] (Read-only) The slots that changed, from 1.

---A chat message arrived.
---@class ChatMessageEventPayload
---@field chatMessage string (Writeable) The text, which callbacks may change.
---@field senderNameSimple string The sender's name.

---An entity is about to be drawn.
---@class EntityDrawEventPayload
---@field entityType EntityType The kind of entity.
---@field npcEntity NPCEntity? The NPC, when it is one.

---The client's state changed.
---@class GameStateChangedEventPayload
---@field oldState GameState The state before.
---@field newState GameState The state now.

---The settings layer opened.
---@class SettingsLayerReadyEventPayload
---@field component ui.Component The layer to build the settings in.

---@class WorldUpdatedEventPayload

---The right-click menu is complete and can be changed.
---@class MiniMenuReadyEventPayload
---@field miniMenu MiniMenu (Read-only) The menu.

---@class Event
---@field Logic EventSubscription<LogicEventPayload, nil> Fifty times a second.
---@field ServerLogic EventSubscription<nil, nil> After each packet from the server.
---@field InventoryUpdated EventSubscription<InventoryUpdatedEventPayload, nil> An inventory changed.
---@field ChatMessage EventSubscription<ChatMessageEventPayload, nil> A chat message arrived.
---@field EntityDraw EventSubscription<EntityDrawEventPayload, boolean> An entity is about to be drawn.
---@field GameStateChanged EventSubscription<GameStateChangedEventPayload, nil> The client's state changed.
---@field SettingsLayerReady EventSubscription<SettingsLayerReadyEventPayload, nil> The settings layer opened.
---@field WorldUpdated EventSubscription<WorldUpdatedEventPayload, nil> The world area changed.
---@field MiniMenuReady EventSubscription<MiniMenuReadyEventPayload, nil> The right-click menu is complete.
Event = {}
