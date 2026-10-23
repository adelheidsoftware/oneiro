-- Errors caught with pcall.

local Parsing = {}

local failures = 0

local function measured(text)
    if text == "" then
        error("empty text")
    end
    if text:sub(1, 1) == "!" then
        error(formatStr("not a measure: {}", text))
    end
    return #text
end

-- Runs the task, and logs and counts a failure instead of passing it on.
---@param task function what to run
function Parsing.run(task)
    local ok, e = pcall(function()
        task()
    end)
    if not ok then
        failures = failures + 1
        log("The task failed:", e)
    end
end

function Parsing.number(text, fallback)
    local ok, result = pcall(function()
        return measured(text)
    end)
    if ok then
        return result
    else
        return fallback
    end
end

function Parsing.outcome(text)
    local ok, result = pcall(function()
        local value = measured(text)
        return formatStr("number {}", value)
    end)
    if ok then
        return result
    else
        local e = result
        -- The message says what went wrong
        return formatStr("error {}", e)
    end
end

function Parsing.strict(text)
    local ok, e = pcall(function()
        measured(text)
    end)
    if not ok then
        if e:find("empty", 1, true) ~= nil then
            return
        end
        error(e, 0)
    end
end

function Parsing.failureCount()
    return failures
end

function Parsing.check(problem)
    local failure = formatStr("broken: {}", problem)
    if problem ~= "" then
        error(failure)
    end
end

function Parsing.message(problem)
    local failure = problem
    return failure
end

return Parsing
