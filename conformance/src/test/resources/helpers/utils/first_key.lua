-- utils/first_key.lua
-- The first key pairs gives, as a TreeMap's firstKey() or a TreeSet's first(); nil for an empty table, where Java
-- throws.

local function firstKey(t)
    for key in pairs(t) do
        return key
    end
    return nil
end

return { firstKey = firstKey }
