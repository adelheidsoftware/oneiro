-- Kotlin's string functions behave in Lua as they do in Kotlin.

local Labels = require("src/labels")

assert(Labels.clock(9, 5) == "09:05", "padStart pads a short number")
assert(Labels.clock(12, 30) == "12:30", "padStart keeps text that is long enough")
assert(Labels.column("guam") == "guam      |", "padEnd pads with spaces")
assert(Labels.column("a very long name") == "a very long name|", "padEnd keeps longer text")

assert(Labels.item("a guam leaves") == "guam leave", "removePrefix, then removeSuffix")
assert(Labels.item("ranarr") == "ranarr", "text without the prefix or the suffix stays as it is")
assert(Labels.item("") == "", "empty text stays empty")

assert(Labels.command("teleport:varrock") == "teleport", "substringBefore")
assert(Labels.argument("teleport:varrock") == "varrock", "substringAfter")
assert(Labels.argument("a:b:c") == "b:c", "substringAfter the first delimiter")
assert(Labels.command("logout") == "logout" and Labels.argument("logout") == "logout",
    "without the delimiter, all of the text")

assert(Labels.hasText("  x ") and not Labels.hasText(" \t ") and not Labels.hasText(""), "isNotBlank")
assert(Labels.isEmptyLine("   ") and Labels.isEmptyLine("") and not Labels.isEmptyLine(" a"), "isBlank")
assert(Labels.indentless("  guam  ") == "guam  ", "trimStart")
assert(Labels.untrailed("  guam  ") == "  guam", "trimEnd")

assert(Labels.short("ranarr") == "ran" and Labels.short("ra") == "ra", "take")
assert(Labels.rest("ranarr") == "arr" and Labels.rest("ra") == "", "drop")
