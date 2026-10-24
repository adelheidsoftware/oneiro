-- src/stage.lua
-- How far a patch has grown.

local Stage = {}
Stage.__index = Stage

function Stage.new(name, ordinal)
    local self = setmetatable({}, Stage)
    self.name = name
    self.ordinal = ordinal
    return self
end

Stage.SEED = Stage.new("SEED", 0)
Stage.GROWING = Stage.new("GROWING", 1)
Stage.DONE = Stage.new("DONE", 2)

-- Orders the constants as they are declared, as Java's natural order does
function Stage.__lt(a, b)
    return a.ordinal < b.ordinal
end

return Stage
