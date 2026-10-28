-- utils/clear.lua
-- Removes every element or entry of the table, as Kotlin's clear().

local function clear(t)
    for key in pairs(t) do
        t[key] = nil
    end
end

return { clear = clear }
