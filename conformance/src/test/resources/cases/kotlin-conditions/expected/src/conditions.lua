-- src/conditions.lua
-- Chains of collection functions inside conditions and other expressions, run before the statement they are in.

local Conditions = {}

-- Whether a drop was rare, tested in an if.
---@param drops table[]
---@return string
function Conditions.rarity(drops)
    local any = false
    for _, it in ipairs(drops) do
        if it.item == "rare" then
            any = true
            break
        end
    end
    if any then
        return "rare"
    end
    return "common"
end

-- How many drops were stacks, and one more.
---@param drops table[]
---@return integer
function Conditions.stacksAndOne(drops)
    local count = 0
    for _, it in ipairs(drops) do
        if it.count > 1 then
            count = count + 1
        end
    end
    return count + 1
end

-- Whether the wanted item dropped, when one is wanted.
---@param drops table[]
---@param wanted string?
---@return boolean
function Conditions.dropped(drops, wanted)
    local both = wanted ~= nil
    if both then
        local any = false
        for _, it in ipairs(drops) do
            if it.item == wanted then
                any = true
                break
            end
        end
        both = any
    end
    return both
end

-- Whether nothing is asked, or every drop is a stack.
---@param drops table[]
---@param asked boolean
---@return boolean
function Conditions.allStacks(drops, asked)
    local either = not asked
    if not either then
        local all = true
        for _, it in ipairs(drops) do
            if not (it.count > 1) then
                all = false
                break
            end
        end
        either = all
    end
    return either
end

-- The total, as text.
---@param drops table[]
---@return string
function Conditions.total(drops)
    local sum = 0
    for _, it in ipairs(drops) do
        sum = sum + it.count
    end
    return formatStr("Total: {}", sum)
end

-- Whether the mood is calm, when the drops are ready.
---@param ready boolean
---@param mood integer
---@return boolean
function Conditions.calm(ready, mood)
    local both = ready
    if both then
        local value
        if mood == 1 then
            value = true
        else
            value = false
        end
        both = value
    end
    return both
end

return Conditions
