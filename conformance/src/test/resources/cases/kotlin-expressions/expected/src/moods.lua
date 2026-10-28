-- src/moods.lua
-- An if or when with blocks inside an expression, whose value a local before the statement takes.

local Moods = {}

-- Declared ahead: used above their definitions below.
local describe, confirmed

-- How a monster feels.
local Mood = { CALM = "CALM", ANGRY = "ANGRY", AFRAID = "AFRAID" }
Moods.Mood = Mood

-- Logs how the monster feels.
---@param mood string
function Moods.report(mood)
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

-- A line about a level, with a note on how far it is past the maximum.
---@param level integer
---@return string
function Moods.level(level)
    local note
    if level > 99 then
        local over = level - 99
        note = formatStr("{} past the maximum", over)
    else
        note = "normal"
    end
    return describe(level, note)
end

function describe(level, note)
    return formatStr("level {}: {}", level, note)
end

-- Whether to warn: always for an angry monster, otherwise as the setting says.
---@param mood string
---@param setting boolean
---@return boolean
function Moods.warn(mood, setting)
    local on
    if mood == Mood.ANGRY then
        on = true
    else
        on = setting
    end
    local warning = confirmed(on)
    return warning
end

function confirmed(on)
    return on
end

-- Logs both moods in one line.
---@param first string
---@param second string
function Moods.both(first, second)
    local text
    if first == Mood.CALM then
        text = "calm"
    else
        text = "upset"
    end
    local text2
    if second == Mood.CALM then
        text2 = "calm"
    else
        text2 = "upset"
    end
    log(formatStr("{} and {}", text, text2))
end

return Moods
