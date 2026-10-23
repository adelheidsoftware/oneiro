-- Stand-ins for the client's tables, with just what the generated module calls, so that it runs under a plain Lua 5.4.
-- Functions called with a colon take the table first, as the client's do.

local logged = {}
function log(...)
    local parts = {}
    for index = 1, select("#", ...) do
        parts[#parts + 1] = tostring(select(index, ...))
    end
    logged[#logged + 1] = table.concat(parts, " ")
end

config = { NPC = { HANS = { name = "Hans" }, MAN = { name = "Man" } } }
id = {
    Obj = {
        COINS = 995,
        LOGS = 1511,
        FromID = function(objectId)
            return "OBJ_" .. objectId
        end,
    },
    NPC = { ["2ND_GUARD"] = 3 },
}
var = { VarPlayer = { QUEST_POINTS = { value = 42 } } }
GameState = { LOBBY = 20, GAME = 30 }
function GameState.FromID(value)
    for name, each in pairs(GameState) do
        if each == value then
            return name
        end
    end
end
SkillType = { ATTACK = 0 }
ModifierKey = { SHIFT = 1, CONTROL = 2 }

local hans = { isValid = true, displayName = "Hans", config = config.NPC.HANS }
local man = { isValid = true, displayName = "Man", config = config.NPC.MAN }
local gone = { isValid = false, displayName = "Mandrith", config = config.NPC.MAN }
NPCList = {
    All = function()
        return { hans, man, gone }
    end,
    Filter = function(settings)
        assert(settings.range == 5 and settings.coordGrid ~= nil, "the settings table is filled in")
        local found = {}
        for _, npc in ipairs({ hans, man }) do
            if settings.func(npc) then
                found[#found + 1] = npc
            end
        end
        return found
    end,
}

local available = false
Player = {
    IsAvailable = function()
        return available
    end,
    GetLevelGameplay = function(skill)
        return skill == SkillType.ATTACK and 99 or 1
    end,
}

local stored = {}
PersistentDB = {
    GetInt = function(self, key)
        assert(self == PersistentDB, "PersistentDB is called with a colon")
        return stored[key]
    end,
    SetInt = function(self, key, value)
        assert(self == PersistentDB, "PersistentDB is called with a colon")
        stored[key] = value
        return true
    end,
}

ui = {
    Text = {
        new = function(parent)
            return {
                parent = parent,
                SetPos = function(self, x, y)
                    self.x, self.y = x, y
                end,
            }
        end,
    },
    Interfaces = {
        GetComponent = function(self, interface, component)
            assert(self == ui.Interfaces, "ui.Interfaces is called with a colon")
            return { interface = interface, component = component }
        end,
    },
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
Event = { GameStateChanged = event("GameStateChanged"), ServerLogic = event("ServerLogic"), EntityDraw = event("EntityDraw") }

local Watch = require("src/watch")

local names = Watch.names("an")
assert(#names == 2 and names[1] == "Hans" and names[2] == "Man", "valid NPCs whose names contain the text")

local near = Watch.near({ x = 1 })
assert(#near == 1 and near[1] == man, "the filter's function leaves Hans out")

assert(Watch.attackOr(5) == 5, "the fallback while no player is available")
available = true
assert(Watch.attackOr(5) == 99, "the level once one is")

assert(Watch.visits() == 1 and Watch.visits() == 2 and stored.visits == 2, "visits count up in PersistentDB")

assert(Watch.describe(995) == "coins" and Watch.describe(1511) == "logs", "named objects")
assert(Watch.describe(7) == "OBJ_7", "others by their config name")
assert(Watch.guardId() == 3, "a name that starts with a digit, read by indexing")

assert(Watch.shifted(3) and not Watch.shifted(2), "the shift bit")

local tile = setmetatable({}, {
    __tostring = function()
        return "(0, 3200, 3200)"
    end,
})
local label = Watch.label("parent", tile)
assert(label.content == "Tile (0, 3200, 3200)", "the tile through tostring: " .. tostring(label.content))
assert(label.x == 10 and label.y == 20 and label.parent == "parent", "the text component is placed")

assert(Watch.chatbox().interface == 137, "a component of an interface")

Watch.watch()
subscriptions["GameStateChanged/watch.state"]({ oldState = GameState.LOBBY, newState = GameState.GAME })
subscriptions["ServerLogic/watch.tick"]()
assert(logged[1] == "Logged in with quest points: 42" and logged[2] == "tick", "the callbacks log")
assert(subscriptions["EntityDraw/watch.draw"]({ npcEntity = man }) == false, "Man is hidden")
assert(subscriptions["EntityDraw/watch.draw"]({ npcEntity = hans }) == true, "Hans is drawn")
assert(subscriptions["EntityDraw/watch.draw"]({}) == true, "entities other than NPCs are drawn")

Watch.logAll({ "a", 2, "c" })
assert(logged[3] == "values: a 2 c", "an array spread over log's values: " .. tostring(logged[3]))
Watch.unwatch()
assert(next(subscriptions) == nil, "every subscription is removed")

assert(Watch.stateText(GameState.GAME) == "state GAME, game", "an API enum's value as text, through FromID")
