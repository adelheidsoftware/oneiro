-- Counts game ticks and backpack changes while the plugin runs.

local Tally = require("src/tally")
local HerbConfig = require("src/herb_config")

local REPORT_EVERY = 100

local tally = Tally.new()
local ticks = 0
local nextReport = REPORT_EVERY

local function report()
    log("Ticks:", ticks, "backpack changes:", tally:sum())
end

local function onServerLogic()
    ticks = ticks + 1
    if ticks == nextReport then
        report()
        nextReport = nextReport + REPORT_EVERY
    end
end

local function onInventoryUpdated(event)
    if event.inv == config.Inv.INV then
        tally:add(#event.slotsUpdated)
    end
end

local function onEntityDraw(event)
    return event.npcEntity == nil or not HerbConfig.hideHans() or event.npcEntity.config ~= config.NPC.HANS
end

-- The greeting is read once, when the plugin starts
if HerbConfig.greet() then
    log("Herb tracker started")
end

Event.ServerLogic.Subscribe("herbs.onServerLogic", onServerLogic)
Event.InventoryUpdated.Subscribe("herbs.onInventoryUpdated", onInventoryUpdated)
Event.EntityDraw.Subscribe("herbs.onEntityDraw", onEntityDraw)

-- The client calls PluginShutdown when the plugin is disabled, and requires it to be global.
function PluginShutdown()
    Event.ServerLogic.Unsubscribe("herbs.onServerLogic")
    Event.InventoryUpdated.Unsubscribe("herbs.onInventoryUpdated")
    Event.EntityDraw.Unsubscribe("herbs.onEntityDraw")
    HerbConfig.setRuns(HerbConfig.runs() + 1)
end
