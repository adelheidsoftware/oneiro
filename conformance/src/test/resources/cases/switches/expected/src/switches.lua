-- src/switches.lua
-- Switches as if/elseif chains.

local Switches = {}

-- An arrow switch, with several labels on one case and a default
function Switches.describe(code)
    local text
    if code == 1 then
        -- The common case first
        text = "one"
    elseif code == 2 or code == 3 then
        text = "a few"
    else
        text = "many"
    end
    return text
end

-- The classic form: a break ends each case, and empty cases share the statements that follow
function Switches.weight(size)
    local weight = 0
    if size == "small" then
        weight = 1
    elseif size == "medium" or size == "large" then
        -- Both count as big
        weight = 5
    else
        weight = -1
    end
    return weight
end

-- The default first; Lua's else comes last
function Switches.isWeekend(day)
    if day == "sat" or day == "sun" then
        return true
    else
        return false
    end
end

-- A selector with a call is read once, into a local
function Switches.amount(values)
    local size = #values
    if size == 0 then
        return "none"
    elseif size == 1 then
        return "one"
    else
        return "several"
    end
end

-- A selector Lua computes is read once too, when more than one case compares it
function Switches.parity(value)
    local selected = math.fmod(value, 2)
    if selected == 0 then
        return "even"
    elseif selected == 1 or selected == -1 then
        return "odd"
    else
        return "impossible"
    end
end

-- A switch expression as the value of a local, with a case that yields from a block
function Switches.price(item, member)
    local price
    if item == "apple" then
        price = 3
    elseif item == "pear" then
        local base = 4
        price = member and base - 1 or base
    else
        price = 0
    end
    return price
end

-- Returned directly
function Switches.season(month)
    if month == 12 or month == 1 or month == 2 then
        return "winter"
    elseif month == 3 or month == 4 or month == 5 then
        return "spring"
    elseif month == 6 or month == 7 or month == 8 then
        return "summer"
    else
        return "autumn"
    end
end

-- Assigned
function Switches.label(level)
    local label = "unknown"
    if level == 0 then
        label = "perfect"
    elseif level == 5 then
        label = "poor"
    else
        label = formatStr("level {}", level)
    end
    return label
end

-- Inside a loop, where continue still jumps to the next value
function Switches.sumOdd(values)
    local sum = 0
    for _, value in ipairs(values) do
        if math.fmod(value, 2) == 0 then
            goto continue
        else
            sum = sum + value
        end
        ::continue::
    end
    return sum
end

-- Empty cases that say why they are empty
function Switches.bonus(level)
    local bonus = 0
    if level == 1 then
        -- Level 1 has no bonus yet
    elseif level == 2 then
        bonus = 5
    else
        -- Any other level keeps none
    end
    return bonus
end

-- A default that does nothing
function Switches.bump(level)
    local bonus = 0
    if level == 1 then
        bonus = 1
    end
    return bonus
end

return Switches
