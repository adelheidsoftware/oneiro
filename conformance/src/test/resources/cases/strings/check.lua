-- What each Java method returns, run through the formatStr stand-in that reproduces the client.
local Strings = require("src/strings")

assert(Strings.greeting("Ann", 3) == "Hello Ann, level 3")
assert(Strings.joined("a", "b") == "ab")
-- A long beyond 2^53 keeps every digit, because it goes through tostring
assert(Strings.tracking(9007199254740993, 0.5, true) == "id 9007199254740993 ratio 0.5 done true")
assert(Strings.formatted(42, 0.256, 7, 255) == "   42|ab    |0.26|ff|000000FF|+7|%")
-- A negative int prints in 32 bits in hexadecimal, as in Java
assert(Strings.formatted(1, 1.0, -5, -1) == "    1|ab    |1.00|ffffffff|FFFFFFFF|-5|%")
assert(Strings.listed({ "a", "b" }) == "names: a b")
assert(Strings.methods("xyz ") == "yzxyz14")
assert(Strings.methods("abc") == "ABC")
assert(Strings.literal() == "a{b}c")
assert(Strings.braced(5) == "{5}")
assert(Strings.counted(3) == "count=3")
assert(Strings.compare("a.lua", "x") and Strings.compare("Ab", "aB") and not Strings.compare("ab", "zz"))
assert(Strings.converted(3, 0.5, 9007199254740993, false) == "330.5false9007199254740993")
assert(Strings.changed("Ab") == "AbAbab")
assert(Strings.parsed("35") == 42)
assert(Strings.joinedAll({ "x", "y" }) == "x, ya-b")
assert(Strings.numbered("apples", 3) == "3 apples")
assert(Strings.built(true, 2.5) == "true2.5")
assert(Strings.specifiers("abcdef", 12345.678) == "abc 1.2e+04\n")
