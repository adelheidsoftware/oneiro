-- src/rankings.lua
-- Streams with more steps: limit and skip, a sort with steps after it, and lambdas of several statements.

local Rankings = {}

-- The first few items.
---@param drops table[]
---@param count integer
---@return string[]
function Rankings.firstItems(drops, count)
    local taken = 0
    local list = {}
    for _, drop in ipairs(drops) do
        if taken == count then
            break
        end
        taken = taken + 1
        list[#list + 1] = drop.item
    end
    return list
end

-- The items of the first half of the drops, a limit worked out once.
---@param drops table[]
---@return string[]
function Rankings.firstHalf(drops)
    local limit = #drops // 2
    local taken = 0
    local list = {}
    for _, drop in ipairs(drops) do
        if taken == limit then
            break
        end
        taken = taken + 1
        list[#list + 1] = drop.item
    end
    return list
end

-- The items after the first.
---@param drops table[]
---@return string[]
function Rankings.laterItems(drops)
    local dropped = 0
    local list = {}
    for _, drop in ipairs(drops) do
        dropped = dropped + 1
        if dropped > 1 then
            list[#list + 1] = drop.item
        end
    end
    return list
end

-- The two most valuable drops: the drops sorted, then the first two taken.
---@param drops table[]
---@return table[]
function Rankings.mostValuable(drops)
    local sorted = table.move(drops, 1, #drops, 1, {})
    table.sort(sorted, function(a, b)
        return b.price < a.price
    end)
    local taken = 0
    local list = {}
    for _, item in ipairs(sorted) do
        if taken == 2 then
            break
        end
        taken = taken + 1
        list[#list + 1] = item
    end
    return list
end

-- The items of the stacks, from the smallest up.
---@param drops table[]
---@return string[]
function Rankings.stackItems(drops)
    local sorted = {}
    for _, drop in ipairs(drops) do
        if drop.count > 1 then
            sorted[#sorted + 1] = drop
        end
    end
    table.sort(sorted, function(a, b)
        return a.count < b.count
    end)
    local list = {}
    for _, drop in ipairs(sorted) do
        list[#list + 1] = drop.item
    end
    return list
end

-- The items of the drops worth keeping, by a test in steps.
---@param drops table[]
---@return string[]
function Rankings.keepers(drops)
    local list = {}
    for _, drop in ipairs(drops) do
        local worth = drop.count * drop.price
        if worth > 150 then
            list[#list + 1] = drop.item
        end
    end
    return list
end

-- Each drop's line, worked out in steps.
---@param drops table[]
---@return string[]
function Rankings.lines(drops)
    local list = {}
    for _, drop in ipairs(drops) do
        local count = formatStr("{}x", drop.count)
        list[#list + 1] = formatStr("{} {}", count, drop.item)
    end
    return list
end

-- How many drops of each group are single, a stream inside a stream's lambda.
---@param groups table[][]
---@return integer[]
function Rankings.singles(groups)
    local list = {}
    for _, group in ipairs(groups) do
        local count = 0
        for _, drop in ipairs(group) do
            if drop.count == 1 then
                count = count + 1
            end
        end
        list[#list + 1] = count
    end
    return list
end

return Rankings
