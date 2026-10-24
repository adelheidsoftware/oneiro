-- src/visits.lua
-- Counts visits; written for threads, which the client's one thread makes no difference to.

local Visits = {}

local count = 0

function Visits.visit()
    count = count + 1
    return count
end

return Visits
