-- utils/first_key.lua
-- The first key pairs gives, as a sorted set's first() or a sorted map's firstKey(); nil for an empty table, where
-- Kotlin throws.

local function firstKey(t)
    for key in pairs(t) do
        return key
    end
    return nil
end

return firstKey
