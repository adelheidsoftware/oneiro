-- utils/quotient.lua
-- Integer division that rounds toward zero, as Kotlin's / does; Lua's // rounds down.

local function quotient(a, b)
    local q = a // b
    if q < 0 and q * b ~= a then
        q = q + 1
    end
    return q
end

return { quotient = quotient }
