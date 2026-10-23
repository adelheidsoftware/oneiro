-- Runs dev/main.lua as the development folder's main.lua does: after the plugin's own main.lua, which has defined its
-- PluginShutdown, so that one shutdown runs both.

local happened = {}
function log(...)
    happened[#happened + 1] = table.concat({ ... }, " ")
end
function ExecuteConsoleCommand(command)
    happened[#happened + 1] = "command " .. command
end

local subscriptions = {}
Event = {
    ServerLogic = {
        Subscribe = function(reference, callback)
            subscriptions[reference] = callback
        end,
        Unsubscribe = function(reference)
            subscriptions[reference] = nil
        end,
    },
}

-- The plugin's own main.lua ran first
function PluginShutdown()
    happened[#happened + 1] = "plugin shut down"
end

require("dev/main")
assert(happened[1] == "command !tele 3058 3311 0", "startUp ran when dev/main.lua loaded")
local onServerLogic = assert(subscriptions["herbs.dev.onServerLogic"], "subscribed under <id>.dev.<method>")
onServerLogic()
onServerLogic()

PluginShutdown()
assert(happened[2] == "plugin shut down", "the plugin's own shutdown runs first")
assert(subscriptions["herbs.dev.onServerLogic"] == nil, "then the dev subscriptions end")
assert(happened[3] == "Ticks while the plugin ran: 2", "and then shutDown runs: " .. tostring(happened[3]))
