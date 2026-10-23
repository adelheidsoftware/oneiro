-- Statement forms, comments and blank lines.
--
-- The second paragraph of the header follows an empty comment line.

local Tracker = {}
Tracker.__index = Tracker

-- Creates a tracker with nothing confirmed.
---@return Tracker
function Tracker.new()
    local self = setmetatable({}, Tracker)
    self.confirmed = {} -- sprite ID -> true
    self.count = 0
    return self
end

function Tracker:confirm(sprite)
    if not self.confirmed[sprite] then
        self.confirmed[sprite] = true
        self.count = self.count + 1
    else
        print("already confirmed")
    end
end

local function countDown(from, ...)
    local total
    for index = from, 1, -1 do
        if index % 2 == 0 then
            goto continue
        end
        total = (total or 0) + index
        ::continue::
    end
    for _, value in ipairs({ ... }) do
        print(value)
    end
    while total > 10 do
        total = total // 2
    end
    repeat
        total = total - 1
    until total <= 0
    -- Nothing else to do here.
end

local first, second = 1, 2
first, second = second, first
while true do
    break
end

return Tracker
