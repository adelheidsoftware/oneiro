// The Lua tree, the printer that writes it in the house style, and the Lua-to-source line map. Knows nothing about
// Java or Kotlin; both front ends lower into it.

plugins {
	`java-library`
}

description = "Oneiro's Lua syntax tree, and the printer that writes it in the style of hand-written RS3 plugins."
