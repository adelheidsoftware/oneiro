-- Counting, grouping and queueing with the collections' own methods.

-- The parts of the text between the separators, as Java's String.split with a plain separator: with no limit, trailing
-- empty parts are dropped; a negative limit keeps them; a positive one makes at most that many parts.
local function split(text, separator, limit)
    if limit == nil then
        limit = 0
    end
    local parts = {}
    local start = 1
    while limit <= 0 or #parts < limit - 1 do
        local first, last = text:find(separator, start, true)
        if first == nil then
            break
        end
        parts[#parts + 1] = text:sub(start, first - 1)
        start = last + 1
    end
    if #parts == 0 then
        return { text }
    end
    parts[#parts + 1] = text:sub(start)
    if limit == 0 then
        while parts[#parts] == "" do
            parts[#parts] = nil
        end
    end
    return parts
end

-- The value under the key, computed and stored first when there is none, as Java's Map.computeIfAbsent.
local function computeIfAbsent(map, key, compute)
    local value = map[key]
    if value == nil then
        value = compute(key)
        map[key] = value
    end
    return value
end

-- Makes a StringBuilder, which is a list of pieces, hold only the text.
local function builderSet(builder, text)
    for i = #builder, 1, -1 do
        builder[i] = nil
    end
    builder[1] = text
end

-- The map as Java writes it, {a=1, b=2}, in Lua's order, which is no more fixed than a HashMap's.
local function mapText(map)
    local parts = {}
    for key, value in pairs(map) do
        parts[#parts + 1] = tostring(key) .. "=" .. tostring(value)
    end
    return "{" .. table.concat(parts, ", ") .. "}"
end

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
