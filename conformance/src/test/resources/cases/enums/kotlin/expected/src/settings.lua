-- src/settings.lua
-- Enums: constants as strings, when over them, and their lists.

local Mode = require("src/mode")

local Settings = {}

-- How loud a notification is.
local Volume = { QUIET = "QUIET", LOUD = "LOUD" }
Settings.Volume = Volume

function Settings.describe(mode)
    if mode == Mode.FAST then
        return "fast"
    elseif mode == Mode.SAFE or mode == Mode.OFF then
        return "careful"
    end
end

function Settings.loudness(volume)
    local loudness = 0
    for _, each in ipairs({ Volume.QUIET, Volume.LOUD }) do
        if each == volume or each == "LOUD" then
            loudness = loudness + 1
        end
    end
    return loudness
end

function Settings.parse(name)
    return Mode[name]
end

function Settings.report(mode, volume)
    return formatStr("mode {} at {}", mode, volume)
end

function Settings.tally(modes)
    local tally = {}
    for _, mode in ipairs(modes) do
        tally[mode] = (tally[mode] or 0) + 1
    end
    return tally
end

return Settings
