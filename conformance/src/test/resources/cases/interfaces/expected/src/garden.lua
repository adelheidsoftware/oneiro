-- A garden that plants, sorts and describes herbs.

local Planter = require("src/planter")
local Describable = require("src/describable")

local Garden = {}

-- Plants the herbs one by one with a planter.
---@param herbs string[]
---@return string[]
function Garden.plant(herbs)
    local planter = Planter.new()
    for _, item in ipairs(herbs) do
        planter(item)
    end
    return planter:all()
end

-- The herbs from the lowest level up.
---@param herbs Herbal[]
---@return Herbal[]
function Garden.byLevel(herbs)
    local sorted = table.move(herbs, 1, #herbs, 1, {})
    table.sort(sorted)
    return sorted
end

-- A herb's description in capitals.
---@param herb Herbal
---@return string
function Garden.shout(herb)
    return Describable.shout(herb)
end

return Garden
