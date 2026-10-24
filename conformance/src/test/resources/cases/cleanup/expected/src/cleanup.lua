-- Work that always tidies up after itself.

local Cleanup = {}

-- A reader of a text, which counts when it is closed.
local Reader = {}
Reader.__index = Reader
Cleanup.Reader = Reader

-- How many readers have been closed.
Reader.closes = 0

function Reader.new(text)
    local self = setmetatable({}, Reader)
    self.text = text
    return self
end

-- The text's first letter; an empty text has none.
---@return string
function Reader:first()
    if self.text == "" then
        error("nothing to read")
    end
    return self.text:sub(1, 1)
end

function Reader:close()
    Reader.closes = Reader.closes + 1
end

-- How many tasks have run, failed or not.
Cleanup.runs = 0

-- Runs the task, and counts the run even when the task fails.
---@param task function
function Cleanup.counted(task)
    local ok, failure = pcall(function()
        task()
    end)
    -- A failed run counts too
    Cleanup.runs = Cleanup.runs + 1
    -- The task's error, if any, goes on to the caller
    if not ok then
        error(failure, 0)
    end
end

-- The text's first letter, or the fallback, counting a run either way.
---@param text string
---@param fallback string
---@return string
function Cleanup.firstOr(text, fallback)
    local ok, result = pcall(function()
        return Reader.new(text):first()
    end)
    if not ok then
        ok, result = pcall(function()
            return fallback
        end)
    end
    Cleanup.runs = Cleanup.runs + 1
    if not ok then
        error(result, 0)
    end
    return result
end

-- The text's first letter, read with a reader that is closed whatever happens.
---@param text string
---@return string
function Cleanup.read(text)
    local reader = Reader.new(text)
    local ok, result = pcall(function()
        return reader:first()
    end)
    reader:close()
    if not ok then
        error(result, 0)
    end
    return result
end

return Cleanup
