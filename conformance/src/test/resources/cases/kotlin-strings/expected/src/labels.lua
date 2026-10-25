-- src/labels.lua
-- Kotlin's own string functions: padding, prefixes and suffixes, the text around a delimiter, and blank text.

-- The text after as many padChar as make it length long, as Kotlin's padStart; longer text stays as it is.
local function padStart(text, length, padChar)
    return padChar:rep(length - #text) .. text
end

-- The text before as many padChar as make it length long, as Kotlin's padEnd; longer text stays as it is.
local function padEnd(text, length, padChar)
    return text .. padChar:rep(length - #text)
end

-- The text without the prefix when it starts with it, as Kotlin's removePrefix.
local function removePrefix(text, prefix)
    if text:sub(1, #prefix) == prefix then
        return text:sub(#prefix + 1)
    end
    return text
end

-- The text without the suffix when it ends with it, as Kotlin's removeSuffix.
local function removeSuffix(text, suffix)
    if suffix ~= "" and text:sub(-#suffix) == suffix then
        return text:sub(1, -#suffix - 1)
    end
    return text
end

-- The text before the first delimiter, or all of it when there is none, as Kotlin's substringBefore.
local function substringBefore(text, delimiter)
    local first = text:find(delimiter, 1, true)
    if first == nil then
        return text
    end
    return text:sub(1, first - 1)
end

-- The text after the first delimiter, or all of it when there is none, as Kotlin's substringAfter.
local function substringAfter(text, delimiter)
    local _, last = text:find(delimiter, 1, true)
    if last == nil then
        return text
    end
    return text:sub(last + 1)
end

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
