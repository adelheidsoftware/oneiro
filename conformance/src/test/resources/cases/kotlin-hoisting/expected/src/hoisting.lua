-- src/hoisting.lua
-- Values Lua reads twice, or makes with statements, read into a local before the statement that uses them.

local Hoisting = {}

-- Marks texts with its tag.
local Labeller = {}
Labeller.__index = Labeller
Hoisting.Labeller = Labeller

function Labeller.new(tag)
    local self = setmetatable({}, Labeller)
    self.tag = tag
    return self
end

function Labeller:label(text)
    return formatStr("{} {}", self.tag, text)
end

local checks = 0

-- Each entry as a line of the ledger.
---@param entries string[]
---@return string[]
function Hoisting.detailed(entries)
    local list = {}
    for _, it in ipairs(entries) do
        list[#list + 1] = formatStr("- {}", it)
    end
    return list
end

-- A header and then the ledger's lines, a call's list added to a builder's.
---@param entries string[]
---@param header string
---@return string[]
function Hoisting.ledger(entries, header)
    local list = {}
    list[#list + 1] = header
    local detailed = Hoisting.detailed(entries)
    table.move(detailed, 1, #detailed, #list + 1, list)
    return list
end

-- The length of a word and one, a scope function on a literal.
---@return integer
function Hoisting.wordLength()
    local text = "herbs"
    return 1 + #text
end

-- A line of stars, built inside a call.
---@param count integer
function Hoisting.stars(count)
    local text = {}
    for _ = 1, count do
        text[#text + 1] = "*"
    end
    log(table.concat(text))
end

-- Two texts built in one statement.
function Hoisting.pair()
    local text = {}
    text[#text + 1] = "a"
    local text2 = {}
    text2[#text2 + 1] = "b"
    log(table.concat(text) .. table.concat(text2))
end

-- A word repeated, built for a local of its own.
---@param word string
---@param times integer
---@return string
function Hoisting.repeated(word, times)
    local text = {}
    for _ = 1, times do
        text[#text + 1] = word
    end
    local repeated = table.concat(text)
    return repeated
end

-- The changes, or "nothing", an if with a chain in a branch.
---@param changes string[]
---@return string
function Hoisting.changed(changes)
    if #changes == 0 then
        return "nothing"
    else
        local parts = {}
        for _, it in ipairs(changes) do
            parts[#parts + 1] = it:upper()
        end
        return table.concat(parts, ", ")
    end
end

-- The changes, or "nothing", logged: the same if inside a call.
---@param changes string[]
function Hoisting.logChanged(changes)
    local text
    if #changes == 0 then
        text = "nothing"
    else
        local parts = {}
        for _, it in ipairs(changes) do
            parts[#parts + 1] = it:upper()
        end
        text = table.concat(parts, ", ")
    end
    log(text)
end

-- The names in brackets, a join with a prefix and a postfix inside a call.
---@param names string[]
function Hoisting.bracketed(names)
    local parts = {}
    for _, item in ipairs(names) do
        parts[#parts + 1] = item
    end
    local text = "[" .. table.concat(parts, ", ") .. "]"
    log(text)
end

-- The first entry, or null.
---@param entries string[]
---@return string?
function Hoisting.nearest(entries)
    return entries[1]
end

-- The first few entries.
---@param entries string[]
---@param count integer
---@return string[]
function Hoisting.top(entries, count)
    local taken = 0
    local list = {}
    for _, item in ipairs(entries) do
        if taken == count then
            break
        end
        taken = taken + 1
        list[#list + 1] = item
    end
    return list
end

-- The length of the nearest entry, or 0, a safe call on a call's value.
---@param entries string[]
---@return integer
function Hoisting.nearestLength(entries)
    local nearest = Hoisting.nearest(entries)
    return nearest and #nearest or 0
end

-- The length of the top entry, or 0, a safe call on the first of a call's list.
---@param entries string[]
---@return integer
function Hoisting.topLength(entries)
    local first = Hoisting.top(entries, 1)[1]
    return first and #first or 0
end

-- Whether the ledger has a sensible number of lines, a call's value compared with both ends of a range.
---@param entries string[]
---@return boolean
function Hoisting.sensible(entries)
    local size = #Hoisting.detailed(entries)
    return size >= 1 and size <= 9
end

-- A setting's value, which may be text or a number.
---@param name string
---@return any
function Hoisting.setting(name)
    return name == "mode" and "fast" or 0
end

-- A setting as text, or null, a call's value cast safely.
---@param name string
---@return string?
function Hoisting.settingText(name)
    local setting = Hoisting.setting(name)
    return type(setting) == "string" and setting or nil
end

-- The middle of a text.
---@param text string
---@return integer
function Hoisting.middle(text)
    return #text // 2
end

-- The char after a text's middle, at a position worked out once.
---@param text string
---@return string
function Hoisting.afterMiddle(text)
    local position = Hoisting.middle(text) + 2
    return text:sub(position, position)
end

-- A labeller of the tag.
---@param tag string
---@return Labeller
function Hoisting.labeller(tag)
    return Labeller.new(tag)
end

-- The names labelled, by a reference to a method of a call's value.
---@param names string[]
---@param tag string
---@return string[]
function Hoisting.labelled(names, tag)
    local labeller = Hoisting.labeller(tag)
    local list = {}
    for _, item in ipairs(names) do
        list[#list + 1] = labeller:label(item)
    end
    return list
end

-- How many entries are long and how many short, two chains in one call.
---@param entries string[]
function Hoisting.sizes(entries)
    local count = 0
    for _, it in ipairs(entries) do
        if #it > 5 then
            count = count + 1
        end
    end
    local count2 = 0
    for _, it in ipairs(entries) do
        if #it <= 5 then
            count2 = count2 + 1
        end
    end
    log(formatStr("{} long, {} short", count, count2))
end

-- A text's length, logged first, a scope function inside an expression.
---@param text string
---@return integer
function Hoisting.logged(text)
    log(text)
    return #text
end

-- A name shouted, worked out in steps inside a call.
---@param name string
function Hoisting.shouted(name)
    local text
    do
        local loud = name:upper()
        text = formatStr("{}!", loud)
    end
    log(text)
end

-- Whether a name is long, counting the checks.
---@param name string
---@return boolean
local function isLong(name)
    checks = checks + 1
    return #name > 3
end

-- Whether the plugin is ready and the name long, checking the name either way, as Kotlin's and does.
---@param ready boolean
---@param name string
---@return boolean
function Hoisting.readyAndLong(ready, name)
    local long = isLong(name)
    return ready and long
end

-- How many checks the names have had.
---@return integer
function Hoisting.checkCount()
    return checks
end

return Hoisting
