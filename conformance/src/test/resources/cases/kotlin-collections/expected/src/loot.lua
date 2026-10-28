-- src/loot.lua
-- The collection functions that cut a chain short, flatten it, and group or pair up what it gives.

local orderedTable = require("utils/ordered_table")

local Loot = {}

-- The items dropped, each once, in the order they first dropped.
---@param drops table[]
---@return string[]
function Loot.items(drops)
    local seen = {}
    local list = {}
    for _, it in ipairs(drops) do
        local value = it.item
        if seen[value] == nil then
            seen[value] = true
            list[#list + 1] = value
        end
    end
    return list
end

-- The first drop of each item.
---@param drops table[]
---@return table[]
function Loot.firstOfEach(drops)
    local seen = {}
    local list = {}
    for _, it in ipairs(drops) do
        local key = it.item
        if seen[key] == nil then
            seen[key] = true
            list[#list + 1] = it
        end
    end
    return list
end

-- The first three stacks of more than one.
---@param drops table[]
---@return table[]
function Loot.firstStacks(drops)
    local taken = 0
    local list = {}
    for _, it in ipairs(drops) do
        if it.count > 1 then
            if taken == 3 then
                break
            end
            taken = taken + 1
            list[#list + 1] = it
        end
    end
    return list
end

-- The first half of the drops.
---@param drops table[]
---@return table[]
function Loot.firstHalf(drops)
    local limit = #drops // 2
    local taken = 0
    local list = {}
    for _, item in ipairs(drops) do
        if taken == limit then
            break
        end
        taken = taken + 1
        list[#list + 1] = item
    end
    return list
end

-- The items after the ones to skip.
---@param drops table[]
---@param skipped integer
---@return string[]
function Loot.after(drops, skipped)
    local dropped = 0
    local list = {}
    for _, it in ipairs(drops) do
        dropped = dropped + 1
        if dropped > skipped then
            list[#list + 1] = it.item
        end
    end
    return list
end

-- The drops before the first coins.
---@param drops table[]
---@return table[]
function Loot.beforeCoins(drops)
    local list = {}
    for _, it in ipairs(drops) do
        if it.item == "coins" then
            break
        end
        list[#list + 1] = it
    end
    return list
end

-- The drops from the first coins on.
---@param drops table[]
---@return table[]
function Loot.fromCoins(drops)
    local dropping = true
    local list = {}
    for _, it in ipairs(drops) do
        dropping = dropping and it.item ~= "coins"
        if not dropping then
            list[#list + 1] = it
        end
    end
    return list
end

-- Every drop of every kill.
---@param kills table[]
---@return table[]
function Loot.allDrops(kills)
    local list = {}
    for _, it in ipairs(kills) do
        for _, item in ipairs(it.drops) do
            list[#list + 1] = item
        end
    end
    return list
end

-- The first five drops of the kills, which stops looking once it has them.
---@param kills table[]
---@return table[]
function Loot.firstFive(kills)
    local taken = 0
    local list = {}
    for _, kill in ipairs(kills) do
        for _, item in ipairs(kill.drops) do
            if taken == 5 then
                break
            end
            taken = taken + 1
            list[#list + 1] = item
        end
        if taken == 5 then
            break
        end
    end
    return list
end

-- Whether any kill dropped the item, which stops looking once one has.
---@param kills table[]
---@param item string
---@return boolean
function Loot.dropped(kills, item)
    local found = false
    for _, it in ipairs(kills) do
        for _, it in ipairs(it.drops) do
            if it.item == item then
                found = true
                break
            end
        end
        if found then
            break
        end
    end
    return found
end

-- The first rare item any kill dropped, which stops looking once it finds one.
---@param kills table[]
---@param rare table<string, true>
---@return string?
function Loot.firstRare(kills, rare)
    local stopped = false
    local found
    for _, kill in ipairs(kills) do
        for _, drop in ipairs(kill.drops) do
            local item = drop.item
            if rare[item] ~= nil then
                found = item
                stopped = true
                break
            end
        end
        if stopped then
            break
        end
    end
    return found
end

-- Each number and ten times it.
---@param limit integer
---@return integer[]
function Loot.tens(limit)
    local list = {}
    for n = 1, limit do
        for _, item in ipairs({ n, n * 10 }) do
            list[#list + 1] = item
        end
    end
    return list
end

-- The drops of each item, in the order the items first dropped.
---@param drops table[]
---@return table<string, table[]>
function Loot.byItem(drops)
    local groups = orderedTable()
    for _, it in ipairs(drops) do
        local key = it.item
        local group = groups[key]
        if group == nil then
            group = {}
            groups[key] = group
        end
        group[#group + 1] = it
    end
    return groups
end

-- The counts of each item's drops.
---@param drops table[]
---@return table<string, integer[]>
function Loot.countsByItem(drops)
    local groups = orderedTable()
    for _, it in ipairs(drops) do
        local key = it.item
        local group = groups[key]
        if group == nil then
            group = {}
            groups[key] = group
        end
        group[#group + 1] = it.count
    end
    return groups
end

-- Each item and its count, the last drop's for an item that dropped more than once.
---@param drops table[]
---@return table<string, integer>
function Loot.counts(drops)
    local map = orderedTable()
    for _, it in ipairs(drops) do
        map[it.item] = it.count
    end
    return map
end

-- Each kill by its monster.
---@param kills table[]
---@return table<string, table>
function Loot.byMonster(kills)
    local map = orderedTable()
    for _, it in ipairs(kills) do
        map[it.monster] = it
    end
    return map
end

-- Each item and the length of its name.
---@param items string[]
---@return table<string, integer>
function Loot.nameLengths(items)
    local map = orderedTable()
    for _, it in ipairs(items) do
        map[it] = #it
    end
    return map
end

return Loot
