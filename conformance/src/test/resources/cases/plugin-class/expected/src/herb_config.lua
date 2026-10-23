-- The herb tracker's settings.
--
-- Each setting is kept in PersistentDB under "herbs." and its key.

local Mode = require("src/mode")

local HerbConfig = {}

-- Greeting: Logs a line when the plugin starts
---@return boolean
function HerbConfig.greet()
    local stored = PersistentDB:GetBool("herbs.greet")
    if stored == nil then
        return true
    end
    return stored
end

-- Hide Hans
---@return boolean
function HerbConfig.hideHans()
    return PersistentDB:GetBool("herbs.hideHans") or false
end

-- Runs: How many times the plugin has stopped
---@return integer
function HerbConfig.runs()
    return PersistentDB:GetInt("herbs.runs") or 0
end

-- Runs: How many times the plugin has stopped
---@param runs integer
function HerbConfig.setRuns(runs)
    PersistentDB:SetInt("herbs.runs", runs)
end

-- Mode
-- What the tracker reports each time.
---@return string
function HerbConfig.mode()
    return Mode[PersistentDB:GetString("herbs.mode")] or Mode.COUNT
end

-- Mode
---@param mode string
function HerbConfig.setMode(mode)
    PersistentDB:SetString("herbs.mode", mode)
end

return HerbConfig
