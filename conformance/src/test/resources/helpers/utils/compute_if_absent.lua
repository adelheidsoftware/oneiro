-- utils/compute_if_absent.lua
-- The value under the key, computed and stored first when there is none, as Java's Map.computeIfAbsent.

local function computeIfAbsent(map, key, compute)
    local value = map[key]
    if value == nil then
        value = compute(key)
        map[key] = value
    end
    return value
end

return { computeIfAbsent = computeIfAbsent }
