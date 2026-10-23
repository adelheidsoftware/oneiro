-- Development helpers: a teleport when the plugin starts, and the ticks it ran for when it stops.

local ticks = 0

local function onServerLogic()
    ticks = ticks + 1
end

ExecuteConsoleCommand("!tele 3058 3311 0")

Event.ServerLogic.Subscribe("herbs.dev.onServerLogic", onServerLogic)

-- The plugin's own shutdown, which runs first.
local pluginShutdown = PluginShutdown
-- The client calls PluginShutdown when the plugin is disabled, and requires it to be global.
function PluginShutdown()
    if pluginShutdown ~= nil then
        pluginShutdown()
    end
    Event.ServerLogic.Unsubscribe("herbs.dev.onServerLogic")
    log("Ticks while the plugin ran:", ticks)
end
