-- A monster at the end of a dungeon.

local Monster = require("src/monster")

local Boss = setmetatable({}, { __index = Monster })
Boss.__index = Boss

function Boss.new(name, level)
    local self = setmetatable(Monster.new(name, level), Boss)
    return self
end

return Boss
