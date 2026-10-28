-- utils/sorted_table.lua
-- A table whose pairs walks its keys in sorted order, as a TreeMap and a TreeSet do in Kotlin; comesBefore orders them,
-- and Lua's < when it is nil.

local function sortedTable(comesBefore)
    return setmetatable({}, {
        __pairs = function(t)
            local keys = {}
            for key in next, t do
                keys[#keys + 1] = key
            end
            table.sort(keys, comesBefore)
            local i = 0
            return function()
                repeat
                    i = i + 1
                    local key = keys[i]
                    -- A key removed since the walk began is passed over
                    if key ~= nil and rawget(t, key) ~= nil then
                        return key, rawget(t, key)
                    end
                until key == nil
            end
        end,
    })
end

return sortedTable
