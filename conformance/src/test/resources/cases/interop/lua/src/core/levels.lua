-- Hand-written stand-in for the Lua module the case's Java declares with @LuaModule.

local Levels = {}

Levels.PERFECT = 0

function Levels.fromPenalty(penalty)
    return penalty // 2
end

return Levels
