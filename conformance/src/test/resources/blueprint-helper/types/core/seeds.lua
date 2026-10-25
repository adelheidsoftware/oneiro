---@meta
-- The functions of Blueprint-Helper's seeds module that the Java rewrite of its core calls. Its own annotations type
-- the tables indexed by level as arrays, but they count from level 0 (Perfect), which a Java list cannot describe, so
-- these type them as maps from level to value.

local Seeds = {}

---@param survivors integer[]
---@return integer count how many different Perfect arrangements the survivors still allow
function Seeds.distinctAnswers(survivors) end

---@param arrangement integer
---@param survivors integer[]
---@return integer swaps the most swaps any survivor's Perfect arrangement is away from the arrangement
function Seeds.farthest(arrangement, survivors) end

--- Scores the arrangement over the survivors in one pass: how many seeds and how many distinct answers each level
--- would keep, and the farthest answer, in swaps from the arrangement, among the seeds that would not be Perfect.
---@param survivors integer[]
---@param arrangement integer
---@return table<integer, integer> counts seeds per level
---@return table<integer, integer> answers distinct answers per level
---@return integer farthest
function Seeds.score(survivors, arrangement) end

--- Splits the survivors by the level each seed gives the arrangement.
---@param survivors integer[]
---@param arrangement integer
---@return table<integer, integer[]> buckets the survivors each level would keep
---@return table<integer, integer> counts how many each level would keep
function Seeds.split(survivors, arrangement) end

return Seeds
