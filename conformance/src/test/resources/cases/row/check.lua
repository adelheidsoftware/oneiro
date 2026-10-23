-- The assertions of Blueprint-Helper's tests/row_test.lua, against the generated module.
local Row = require("src/core/row")

assert(Row.complete({ 25074, 25081, 26156, 26158, 26214 }), "five modules make a complete row")
assert(not Row.complete({ 25074, 25081, 26156, 26158, -1 }), "an empty last slot makes it incomplete")
assert(not Row.complete({ 25074, -1, 26156, 26158, 26214 }), "so does an empty slot in the middle")
assert(not Row.complete({ 25074, 25081, 26156, 26158 }), "a missing fifth slot makes it incomplete")
assert(not Row.complete({}), "an empty table is incomplete")
assert(not Row.complete(nil), "nil is incomplete")
