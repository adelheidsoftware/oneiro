-- src/tally.lua
-- Counting, grouping and queueing with the collections' own methods.

local split = require("utils/split")
local computeIfAbsent = require("utils/compute_if_absent")
local builderSet = require("utils/builder_set")
local mapText = require("utils/map_text")

local Tally = {}

-- How often each word comes up.
---@param words string[]
---@return table<string, integer>
function Tally.counts(words)
    local counts = {}
    for _, word in ipairs(words) do
        counts[word] = (counts[word] or 0) + 1
    end
    return counts
end

-- The words by their first letter.
---@param words string[]
---@return table<string, string[]>
function Tally.byInitial(words)
    local groups = {}
    for _, word in ipairs(words) do
        table.insert(
            computeIfAbsent(groups, word:sub(1, 1), function(initial)
                return {}
            end),
            word
        )
    end
    return groups
end

-- Drops the short words, and the empty ones from the set.
---@param words string[]
---@param seen table<string, true>
function Tally.prune(words, seen)
    for i = #words, 1, -1 do
        local word = words[i]
        if #word < 3 then
            table.remove(words, i)
        end
    end
    for word in pairs(seen) do
        if word == "" then
            seen[word] = nil
        end
    end
end

-- Whether a feature is on: one nobody set is on.
---@param features table<string, boolean>
---@param name string
---@return boolean
function Tally.enabled(features, name)
    return features[name] ~= false
end

-- The fields of a line of comma-separated values.
---@param line string
---@return string[]
function Tally.fields(line)
    return split(line, ",")
end

-- Puts a job at each end of the queue, and takes the one at the front.
---@param jobs string[]
---@return string
function Tally.next(jobs)
    jobs[#jobs + 1] = "later"
    table.insert(jobs, 1, "urgent")
    return table.remove(jobs, 1)
end

-- The counts as Java writes a map.
---@param counts table<string, integer>
---@return string
function Tally.describe(counts)
    return formatStr("counts: {}", mapText(counts))
end

-- Notes the tick a name was first seen at.
---@param firstSeen table<string, integer>
---@param name string
---@param tick integer
function Tally.remember(firstSeen, name, tick)
    if firstSeen[name] == nil then
        firstSeen[name] = tick
    end
end

-- The word reversed and bracketed, built up in place.
---@param word string
---@return string
function Tally.reversed(word)
    local text = { word }
    builderSet(text, table.concat(text):reverse())
    local text2 = table.concat(text)
    builderSet(text, text2:sub(1, 0) .. "[" .. text2:sub(1))
    text[#text + 1] = "]"
    return table.concat(text)
end

return Tally
