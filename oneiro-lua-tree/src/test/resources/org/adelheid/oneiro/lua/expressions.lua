-- Expressions: parentheses only where Lua needs them, unary operators, strings and other literals.

local a = (x + y) * z
local b = x - (y - z)
local c = x - y - z
local d = x .. y .. z
local e = 2 ^ -x
local f = (-x) ^ 2
local g = -(-x)
local h = not (p and q)
local i = -(x + y)
local j = #list + 1
local k = ("x"):rep(3)
local l = { 1, 2, 3 }
local m = { [key] = value, name = "text" }
local n = 'say "hi"'
local o = "it's"
local p = "tab\tline\n"
local q = 0xFF + 1.5 + 1e3
local r = nil
local s = true and not false
local t = a.b.c[d]:e(f)
local u = [[
first line
second line]]
local v = ...
local w = math.mininteger
local y = {
    -- The first.
    FIRST = "FIRST",
    SECOND = "SECOND",
}
