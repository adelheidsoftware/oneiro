-- src/watch.lua
-- Calls into the RS3 API: global tables, singletons, objects, constants, enums and events.

local orderedTable = require("utils/ordered_table")
local putEntries = require("utils/put_entries")

local Watch = {}

function Watch.names(text)
    local found = {}
    for _, npc in ipairs(NPCList.All()) do
        -- An entity can leave the view between ticks
        if npc.isValid and npc.displayName:find(text, 1, true) ~= nil then
            found[#found + 1] = npc.displayName
        end
    end
    return found
end

-- The NPCs near a tile, other than Hans.
---@param tile CoordGrid where to look
---@return NPCEntity[]
function Watch.near(tile)
    local settings = {
        coordGrid = tile,
        range = 5,
        func = function(npc)
            return npc.config ~= config.NPC.HANS
        end,
    }
    return NPCList.Filter(settings)
end

function Watch.attackOr(fallback)
    if not Player.IsAvailable() then
        return fallback
    end
    return Player.GetLevelGameplay(SkillType.ATTACK)
end

function Watch.visits()
    local visits = (PersistentDB:GetInt("visits") or 0) + 1
    PersistentDB:SetInt("visits", visits)
    return visits
end

function Watch.describe(objectId)
    if objectId == id.Obj.COINS then
        return "coins"
    elseif objectId == id.Obj.LOGS then
        return "logs"
    else
        return id.Obj.FromID(objectId)
    end
end

function Watch.shifted(modifiers)
    return modifiers & ModifierKey.SHIFT ~= 0
end

function Watch.label(parent, tile)
    local text = ui.Text.new(parent)
    text.content = formatStr("Tile {}", tostring(tile))
    text:SetPos(10, 20)
    return text
end

function Watch.chatbox()
    return ui.Interfaces:GetComponent(137, 2)
end

-- A hook's callback gets the component, then the hook's own values, which Kotlin checks against the hook.
---@param label ui.Text
function Watch.clicks(label)
    label:Subscribe(ui.Hook.ONCLICK, function(_, x, y)
        label.content = formatStr("Clicked at {}, {}", x, y)
    end)
    label:Subscribe(ui.Hook.ONTIMER, "watch.timer", function(self_)
        label.hidden = false
    end)
end

-- Unnamed parameters at the end are left out, as Lua drops the values a function does not name.
---@param label ui.Text
function Watch.clickAnywhere(label)
    label:Subscribe(ui.Hook.ONCLICK, function(_, x)
        label.content = formatStr("Clicked at {}", x)
    end)
    label:Subscribe(ui.Hook.ONTIMER, "watch.hide", function()
        label.hidden = true
    end)
end

-- A callback that returns false stops the listeners after it, as a Lua one may.
---@param label ui.Text
function Watch.clicksOnRight(label)
    label:Subscribe(ui.Hook.ONCLICK, function(_, x, y)
        return x > 10
    end)
end

function Watch.unclick(label)
    label:Unsubscribe(ui.Hook.ONCLICK)
end

-- The client's vectors add and scale with Lua's own operators.
---@param position Vector2
---@param offset Vector2
---@return Vector2
function Watch.nudged(position, offset)
    return (position + offset) * 2.0
end

function Watch.watch()
    Event.GameStateChanged.Subscribe("watch.state", function(event)
        if event.newState == GameState.GAME then
            log("Logged in with quest points:", var.VarPlayer.QUEST_POINTS.value)
        end
    end)
    Event.ServerLogic.Subscribe("watch.tick", function()
        log("tick")
    end)
    Event.EntityDraw.Subscribe("watch.draw", function(event)
        return (event.npcEntity and event.npcEntity.config) ~= config.NPC.MAN
    end)
end

-- An API enum's constants are plain Lua values, so they can key a table.
---@return table<GameState, string>
function Watch.stateNames()
    return putEntries(orderedTable(), { GameState.GAME, "in game" })
end

-- An API enum's value as text is its name, which the client's FromID gives.
---@param state GameState
---@return string
function Watch.stateText(state)
    return formatStr("state {}, {}", GameState.FromID(state), GameState.FromID(state):lower())
end

-- An array spread over a Lua function's values, as Kotlin spreads it over the parameters.
---@param values any[]
function Watch.logAll(values)
    log("values:", table.unpack(values))
end

-- A name that starts with a digit is no Lua name, so Lua indexes the table with it.
---@return integer
function Watch.guardId()
    return id.NPC["2ND_GUARD"]
end

function Watch.unwatch()
    Event.GameStateChanged.Unsubscribe("watch.state")
    Event.ServerLogic.Unsubscribe("watch.tick")
    Event.EntityDraw.Unsubscribe("watch.draw")
end

return Watch
