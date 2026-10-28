-- src/panel.lua
-- A panel of sections, each with its lines.

local Panel = {}

-- How many lines a section shows.
---@param section string
---@param lines string[]
---@return integer
function Panel.show(section, lines)
    return #lines
end

-- The session's section, whose lines are too long for one line together.
---@param xp integer
---@param levels integer
---@return integer
function Panel.session(xp, levels)
    return Panel.show("session", {
        formatStr("experience gained in the session so far: {}", xp),
        formatStr("levels gained in the session so far: {}", levels),
        "the worth of the backpack, at its items' values",
    })
end

return Panel
