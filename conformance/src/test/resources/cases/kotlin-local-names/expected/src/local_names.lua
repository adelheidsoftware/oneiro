-- src/local_names.lua
-- The names of the locals Oneiro makes up: each once in a function, and a list's from where it goes.

local LocalNames = {}

-- Lines of text a section keeps, in its properties.
local Sections = {}
Sections.__index = Sections
LocalNames.Sections = Sections

function Sections.new()
    local self = setmetatable({}, Sections)
    self.lines = {}
    self.rows = { {} }
    return self
end

-- Keeps the section's name as its lines, a list built for a property.
---@param section string
---@param count integer
function Sections:keep(section, count)
    local lines = {}
    for _ = 1, count do
        lines[#lines + 1] = section
    end
    self.lines = lines
end

-- Makes the section's name the first row, a list built for an element of a property.
---@param section string
function Sections:first(section)
    local rows = {}
    rows[#rows + 1] = section
    self.rows[1] = rows
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
        for _, it in ipairs(group) do
            if it > 1 then
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
    for _, it in ipairs(names) do
        texts[#texts + 1] = it:upper()
    end
    LocalNames.show("loud", texts)
    local list = {}
    for _, it in ipairs(names) do
        list[#list + 1] = it:lower()
    end
    LocalNames.show("quiet", list)
end

-- How many names are empty, and how many long, beside a local of its own named count.
---@param names string[]
---@return string
function LocalNames.counts(names)
    local count = #names
    local count2 = 0
    for _, it in ipairs(names) do
        if it == "" then
            count2 = count2 + 1
        end
    end
    local count3 = 0
    for _, it in ipairs(names) do
        if #it > 3 then
            count3 = count3 + 1
        end
    end
    return formatStr("{}: {} empty, {} long", count, count2, count3)
end

-- The first three names.
---@param names string[]
---@return string[]
function LocalNames.top(names)
    local taken = 0
    local list = {}
    for _, item in ipairs(names) do
        if taken == 3 then
            break
        end
        taken = taken + 1
        list[#list + 1] = item
    end
    return list
end

-- The first names in rows of two, from a list a call gives, which a local takes before the loop.
---@param names string[]
function LocalNames.rows(names)
    local top = LocalNames.top(names)
    local texts = {}
    for i = 1, #top, 2 do
        local it = table.move(top, i, math.min(i + 1, #top), 1, {})
        texts[#texts + 1] = table.concat(it, " ")
    end
    LocalNames.show("rows", texts)
end

return LocalNames
