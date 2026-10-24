-- A row is the five track slots of the Discovery window as the reader sees them: an array of five sprite IDs, -1
-- where a slot is empty. This is the one place that says when a row is complete, which is when a reading can refer
-- to it, a puzzle identity can be built from it, and every module on it has a target slot.

local Row = {}

---@param row integer[]? five sprite IDs, -1 for an empty slot
---@return boolean complete true when every one of the five slots holds a module
function Row.complete(row)
    if row == nil then
        return false
    end
    for slot = 1, 5 do
        if row[slot] == nil or row[slot] < 0 then
            return false
        end
    end
    return true
end

return Row
