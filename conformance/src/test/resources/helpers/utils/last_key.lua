-- utils/last_key.lua
-- The last key pairs gives, as a TreeMap's lastKey() or a TreeSet's last(); nil for an empty table, where Java throws.

local function lastKey(t)
    local last = nil
    for key in pairs(t) do
        last = key
    end
    return last
end

return lastKey
