-- Each function matches as Lua's string library does.

local ChatLines = require("src/chat_lines")

assert(ChatLines.caught("You catch a shark.") == "shark", "match gives the capture")
assert(ChatLines.caught("You catch an eel.") == "eel", "an optional letter in the pattern")
assert(ChatLines.caught("You fail to catch anything.") == nil, "match gives nil for no match")

local drop = ChatLines.drop("Drop: 3 x rune bars")
assert(#drop == 2 and drop[1] == "3" and drop[2] == "rune bars", "captures gives every capture")
assert(#ChatLines.drop("No drop") == 0, "captures gives an empty list for no match")

assert(ChatLines.firstNumber("abc 12") == 4, "find counts from 0")
assert(ChatLines.firstNumber("none") == -1, "find gives -1 for no match")

local masked = ChatLines.masked("Level 99, xp 13034431")
assert(masked == "Level #, xp #" and math.type(masked) == nil, "gsub gives only the text")

local words = ChatLines.words("You catch a shark.")
assert(#words == 4 and words[1] == "You" and words[4] == "shark", "gmatch walks each match")
