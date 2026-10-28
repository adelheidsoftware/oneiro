-- utils/last_key.lua
-- The last key pairs gives, as a sorted set's last() or a sorted map's lastKey(); nil for an empty table, where Kotlin
-- throws.

local function lastKey(t)
    local last = nil
    for key in pairs(t) do
        last = key
    end
    return last
end

return { lastKey = lastKey }
