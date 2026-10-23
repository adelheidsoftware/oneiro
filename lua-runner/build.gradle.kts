// Runs Lua 5.4 for Oneiro's test tasks and its conformance suite: the bundled Lua 5.4.8 (LuaJava's native builds of
// the reference implementation), or an installed interpreter the developer names instead.

plugins {
	`java-library`
}

dependencies {
	implementation(libs.luajava.lua54)
	runtimeOnly(variantOf(libs.luajava.lua54.platform) { classifier("natives-desktop") })
}

tasks.test {
	// LuaJava loads its native library through System.load, which JDK 25 reports unless native access is granted
	jvmArgs("--enable-native-access=ALL-UNNAMED")
}
