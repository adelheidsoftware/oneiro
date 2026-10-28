-- Test-only stand-in for the RS3 client's require, so that tests load modules as client 951-1 does. Its require runs
-- a file once, keeps what it gives, and passes on only a table: a file that returns nothing gives nil, and one that
-- returns any other value, such as a function, gives nil in the client too, which this stand-in makes an error, so
-- that the test fails where the plugin would. Never shipped.

local standard = require

function require(name)
    local value = standard(name)
    if type(value) == "table" then
        return value
    elseif value == true then
        -- What standard Lua keeps for a file that returns nothing
        return nil
    end
    error(
        string.format(
            "require(%q) returns a %s, but the client's require passes on only a table, and gives nil for any other value",
            name,
            type(value)
        ),
        2
    )
end
