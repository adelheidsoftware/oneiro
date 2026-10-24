-- src/core/row.lua
-- A row is the five track slots of the Discovery window as the reader sees them: a list of five sprite IDs, -1 where
-- a slot is empty. This is the one place that says when a row is complete.

local Row = {}

---@param row integer[]? five sprite IDs, -1 for an empty slot
---@return boolean complete true when every one of the five slots holds a module
function Row.complete(row)
    if row == nil then
        return false
    end
    for slot = 1, 5 do
        -- A hole in the list or a negative ID both mean the slot is empty
        local id = row[slot]
        if id == nil or id < 0 then
            return false
        end
    end
    return true
end

return Row
