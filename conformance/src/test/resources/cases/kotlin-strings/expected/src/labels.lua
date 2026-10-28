-- src/labels.lua
-- Kotlin's own string functions: padding, prefixes and suffixes, the text around a delimiter, and blank text.

local padStart = require("utils/pad_start").padStart
local padEnd = require("utils/pad_end").padEnd
local removePrefix = require("utils/remove_prefix").removePrefix
local removeSuffix = require("utils/remove_suffix").removeSuffix
local substringBefore = require("utils/substring_before").substringBefore
local substringAfter = require("utils/substring_after").substringAfter

local Labels = {}

-- A time of day as a clock shows it, 09:05.
---@param hours integer
---@param minutes integer
---@return string
function Labels.clock(hours, minutes)
    return formatStr("{}:{}", padStart(tostring(hours), 2, "0"), padStart(tostring(minutes), 2, "0"))
end

-- The name in a column ten wide.
---@param name string
---@return string
function Labels.column(name)
    return formatStr("{}|", padEnd(name, 10, " "))
end

-- An item's name without its article and its plural's s.
---@param text string
---@return string
function Labels.item(text)
    return removeSuffix(removePrefix(text, "a "), "s")
end

-- The command in a line such as "teleport:varrock".
---@param line string
---@return string
function Labels.command(line)
    return substringBefore(line, ":")
end

-- The argument in a line such as "teleport:varrock".
---@param line string
---@return string
function Labels.argument(line)
    return substringAfter(line, ":")
end

-- Whether the line has text other than spaces.
---@param line string
---@return boolean
function Labels.hasText(line)
    return line:find("%S") ~= nil
end

-- Whether the line has nothing but spaces.
---@param line string
---@return boolean
function Labels.isEmptyLine(line)
    return line:find("%S") == nil
end

-- The line without its leading spaces.
---@param line string
---@return string
function Labels.indentless(line)
    return line:match("^%s*(.*)")
end

-- The line without its trailing spaces.
---@param line string
---@return string
function Labels.untrailed(line)
    return line:match("^(.-)%s*$")
end

-- The first three letters of a name.
---@param name string
---@return string
function Labels.short(name)
    return name:sub(1, 3)
end

-- A name after its first three letters.
---@param name string
---@return string
function Labels.rest(name)
    return name:sub(4)
end

return Labels
