-- Runs main.lua as the client would: with the client's tables stood in, it loads once, subscribes, receives events,
-- and shuts down.

local logged = {}
function log(...)
    local parts = {}
    for index = 1, select("#", ...) do
        parts[#parts + 1] = tostring(select(index, ...))
    end
    logged[#logged + 1] = table.concat(parts, " ")
end

config = { Inv = { INV = { id = 93 }, BANK = { id = 95 } }, NPC = { HANS = { name = "Hans" }, MAN = { name = "Man" } } }

local stored = {}
local function typed(kind)
    return function(self, key)
        assert(self == PersistentDB, "PersistentDB is called with a colon")
        local entry = stored[key]
        if entry == nil or entry.kind ~= kind then
            return nil
        end
        return entry.value
    end
end
local function setter(kind)
    return function(self, key, value)
        assert(self == PersistentDB, "PersistentDB is called with a colon")
        stored[key] = { kind = kind, value = value }
        return true
    end
end
PersistentDB = {
    GetBool = typed("bool"),
    SetBool = setter("bool"),
    GetInt = typed("int"),
    SetInt = setter("int"),
    GetString = typed("string"),
    SetString = setter("string"),
}

local subscriptions = {}
local function event(name)
    return {
        Subscribe = function(reference, callback)
            subscriptions[name .. "/" .. reference] = callback
        end,
        Unsubscribe = function(reference)
            subscriptions[name .. "/" .. reference] = nil
        end,
    }
end
Event = {
    ServerLogic = event("ServerLogic"),
    InventoryUpdated = event("InventoryUpdated"),
    EntityDraw = event("EntityDraw"),
}

stored["herbs.runs"] = { kind = "int", value = 2 }
require("main")
assert(logged[1] == "Herb tracker started", "startUp ran when main.lua loaded, with the default greeting")
assert(subscriptions["ServerLogic/herbs.onServerLogic"], "the subscriptions are made under <id>.<method>")

for _ = 1, 100 do
    subscriptions["ServerLogic/herbs.onServerLogic"]()
end
subscriptions["InventoryUpdated/herbs.onInventoryUpdated"]({ inv = config.Inv.BANK, slotsUpdated = { 1 } })
subscriptions["InventoryUpdated/herbs.onInventoryUpdated"]({ inv = config.Inv.INV, slotsUpdated = { 1, 2 } })
for _ = 1, 100 do
    subscriptions["ServerLogic/herbs.onServerLogic"]()
end
assert(logged[2] == "Ticks: 100 backpack changes: 0", "the first report: " .. tostring(logged[2]))
assert(logged[3] == "Ticks: 200 backpack changes: 2", "only backpack changes count: " .. tostring(logged[3]))

local hans = { npcEntity = { config = config.NPC.HANS } }
assert(subscriptions["EntityDraw/herbs.onEntityDraw"](hans) == true, "Hans is drawn by default")
stored["herbs.hideHans"] = { kind = "bool", value = true }
assert(subscriptions["EntityDraw/herbs.onEntityDraw"](hans) == false, "and hidden with the setting")

PluginShutdown()
assert(next(subscriptions) == nil, "PluginShutdown unsubscribes")
assert(stored["herbs.runs"].value == 3, "and runs shutDown, which counts the run")

local HerbConfig = require("src/herb_config")
assert(HerbConfig.mode() == "COUNT", "an enum setting's default")
HerbConfig.setMode("LIST")
assert(HerbConfig.mode() == "LIST", "a stored enum setting")
stored["herbs.mode"] = { kind = "string", value = "NOT_A_MODE" }
assert(HerbConfig.mode() == "COUNT", "a stored name that is no constant reads as the default")
stored["herbs.greet"] = { kind = "bool", value = false }
assert(HerbConfig.greet() == false, "a stored false stays false")
