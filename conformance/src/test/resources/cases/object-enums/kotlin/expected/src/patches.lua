-- src/patches.lua
-- The herbs planted in a farmer's patches.

local Herb = require("src/herb")
local Stage = require("src/stage")
local trim = require("utils/trim")

local Patches = {}

-- The herbs a farmer of the level can plant, as text.
---@param farming integer
---@return string
function Patches.plantable(farming)
    local text = {}
    for _, herb in ipairs({ Herb.GUAM, Herb.MARRENTILL, Herb.RANARR }) do
        if herb:canPlant(farming) then
            text[#text + 1] = herb.name
            text[#text + 1] = " "
        end
    end
    return trim(table.concat(text))
end

-- How many patches grow each herb.
---@param planted Herb[]
---@return table<Herb, integer>
function Patches.counts(planted)
    local counts = {}
    for _, herb in ipairs(planted) do
        counts[herb] = (counts[herb] or 0) + 1
    end
    return counts
end

-- The herb a saved name stands for.
---@param name string
---@return Herb
function Patches.saved(name)
    return Herb[name]
end

-- Whether the first herb comes before the second.
---@param first Herb
---@param second Herb
---@return boolean
function Patches.earlier(first, second)
    return first.ordinal < second.ordinal
end

-- The stage's place in the growth, from 0.
---@param stage Stage
---@return integer
function Patches.step(stage)
    return stage.ordinal
end

-- The tiers from the easiest up, in the order the enum declares them.
---@param tiers Tier[]
---@return Tier[]
function Patches.easiestFirst(tiers)
    local sorted = table.move(tiers, 1, #tiers, 1, {})
    table.sort(sorted)
    return sorted
end

-- What a herb's patch looks like at the stage.
---@param herb Herb
---@param stage Stage
---@return string
function Patches.look(herb, stage)
    if stage == Stage.SEED then
        return formatStr("a seed of {}", herb:describe())
    elseif stage == Stage.GROWING then
        return formatStr("growing {}", herb.name)
    elseif stage == Stage.DONE then
        return formatStr("{} ready", herb.name)
    end
end

return Patches
