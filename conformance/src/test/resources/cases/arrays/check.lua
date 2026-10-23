-- Arrays of arrays, and functions that take any number of values.

local Grids = require("src/grids")

local board = Grids.board(2, 3)
assert(#board == 2 and #board[1] == 3 and board[2][3] == false, "rows of columns, all false")
Grids.mark(board, 1, 2)
assert(board[2][3] == true and board[1][3] == false, "each row is a table of its own")
assert(Grids.total() == 0 and Grids.total(4, 5, 6) == 15, "any number of values")
assert(Grids.both({ 10, 20 }) == 33, "an array spread over the varargs")
assert(Grids.listed("herbs", "guam", "tarromin") == "herbs: guam, tarromin", "varargs after a parameter")
