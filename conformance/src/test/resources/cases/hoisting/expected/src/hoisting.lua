-- src/hoisting.lua
-- Values Lua reads twice, or makes with statements, read into a local before the statement that uses them.

local Hoisting = {}

-- How a monster feels.
local Mood = { CALM = "CALM", ANGRY = "ANGRY", AFRAID = "AFRAID" }
Hoisting.Mood = Mood

-- Logs how the monster feels: a switch inside a call.
---@param mood string
function Hoisting.report(mood)
    local text
    if mood == Mood.CALM then
        text = "calm"
    elseif mood == Mood.ANGRY then
        text = "angry"
    elseif mood == Mood.AFRAID then
        text = "afraid"
    end
    log(formatStr("The monster is {}", text))
end

local function confirmed(on)
    return on
end

-- Whether to warn: a conditional of booleans inside a call.
---@param mood string
---@param setting boolean
---@return boolean
function Hoisting.warn(mood, setting)
    local on
    if mood == Mood.ANGRY then
        on = true
    else
        on = setting
    end
    local warning = confirmed(on)
    return warning
end

-- How many names are empty, logged: a stream inside a call.
---@param names string[]
function Hoisting.empties(names)
    local count = 0
    for _, name in ipairs(names) do
        if name == "" then
            count = count + 1
        end
    end
    log(formatStr("{} empty", count))
end

-- How many names are long and how many short: two streams in one call.
---@param names string[]
function Hoisting.sizes(names)
    local count = 0
    for _, name in ipairs(names) do
        if #name > 5 then
            count = count + 1
        end
    end
    local count2 = 0
    for _, name in ipairs(names) do
        if #name <= 5 then
            count2 = count2 + 1
        end
    end
    log(formatStr("{} long, {} short", count, count2))
end

-- Whether the wanted drop dropped, when one is wanted: a stream on the right of &&.
---@param drops string[]
---@param wanted string
---@return boolean
function Hoisting.dropped(drops, wanted)
    local both = wanted ~= ""
    if both then
        local any = false
        for _, drop in ipairs(drops) do
            if drop == wanted then
                any = true
                break
            end
        end
        both = any
    end
    return both
end

-- The changes, or "nothing": a conditional with a stream in a branch.
---@param changes string[]
---@return string
function Hoisting.changed(changes)
    if #changes == 0 then
        return "nothing"
    else
        local parts = {}
        for _, change in ipairs(changes) do
            parts[#parts + 1] = change:upper()
        end
        return table.concat(parts, ", ")
    end
end

-- Each entry as a line of the ledger.
---@param entries string[]
---@return string[]
function Hoisting.detailed(entries)
    local list = {}
    for _, entry in ipairs(entries) do
        list[#list + 1] = formatStr("- {}", entry)
    end
    return list
end

-- A header and then the ledger's lines, a call's list added to another.
---@param entries string[]
---@param header string
---@return string[]
function Hoisting.ledger(entries, header)
    local lines = {}
    lines[#lines + 1] = header
    local detailed = Hoisting.detailed(entries)
    table.move(detailed, 1, #detailed, #lines + 1, lines)
    return lines
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

-- Adds one to the count at a text's middle, a position worked out once.
---@param counts integer[]
---@param text string
function Hoisting.bump(counts, text)
    local index = Hoisting.middle(text) + 1
    counts[index] = counts[index] + 1
end

return Hoisting
