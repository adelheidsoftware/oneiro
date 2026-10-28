-- src/local_names.lua
-- The locals Oneiro names: each name once in a function, none a global's, a list's after where it goes.

local Kinds = require("src/kinds")

local LocalNames = {}

-- Lines of text a section keeps.
local Sections = {}
Sections.__index = Sections
LocalNames.Sections = Sections

function Sections.new()
    local self = setmetatable({}, Sections)
    self.lines = {}
    return self
end

-- Keeps the names in capitals, a list built for a field.
---@param names string[]
function Sections:keep(names)
    local lines = {}
    for _, name in ipairs(names) do
        lines[#lines + 1] = name:upper()
    end
    self.lines = lines
end

-- Shows a titled list of texts.
---@param title string
---@param texts string[]
function LocalNames.show(title, texts)
    log(title, #texts)
end

-- How many groups have more than one big value: a count inside a count's lambda.
---@param groups integer[][]
---@return integer
function LocalNames.busyGroups(groups)
    local count2 = 0
    for _, group in ipairs(groups) do
        local count = 0
        for _, value in ipairs(group) do
            if value > 1 then
                count = count + 1
            end
        end
        if count > 1 then
            count2 = count2 + 1
        end
    end
    return count2
end

-- The names shown twice: a list handed to a parameter, named after it, and then another.
---@param names string[]
function LocalNames.showBoth(names)
    local texts = {}
    for _, name in ipairs(names) do
        texts[#texts + 1] = name:upper()
    end
    LocalNames.show("loud", texts)
    local list = {}
    for _, name in ipairs(names) do
        list[#list + 1] = name:lower()
    end
    LocalNames.show("quiet", list)
end

-- How many names are empty, and how many long, beside a local of its own named count.
---@param names string[]
---@return string
function LocalNames.counts(names)
    local count = #names
    local count2 = 0
    for _, name in ipairs(names) do
        if name == "" then
            count2 = count2 + 1
        end
    end
    local count3 = 0
    for _, name in ipairs(names) do
        if #name > 3 then
            count3 = count3 + 1
        end
    end
    return formatStr("{}: {} empty, {} long", count, count2, count3)
end

-- A value's kind in words, logged, and whether it is text: a switch's local beside the log() it would hide.
---@param value any
---@return string
function LocalNames.describe(value)
    Kinds.look(value)
    local described
    local log2 = Kinds.log()
    if log2 == "number" then
        described = "a number"
    elseif log2 == "other" then
        described = "something"
    else
        described = "unknown"
    end
    log(described)
    return type(value) == "string" and formatStr("{}, text", described) or described
end

return LocalNames
