import java.util.zip.ZipFile

// The Kotlin front end: analyses a plugin's Kotlin sources with the Kotlin Analysis API, standalone, checks them
// against the supported language (docs/kotlin-subset.md) and lowers each file into the Lua tree. It is written in
// Kotlin, the language the Analysis API is made for, and runs in a process of its own: the Analysis API brings the
// IntelliJ platform with it and keeps global state.

plugins {
	`java-library`
	alias(libs.plugins.kotlin.jvm)
}

description = "Oneiro's Kotlin front end, which compiles RS3 plugins written in Kotlin to Lua 5.4."

kotlin {
	explicitApi()
	compilerOptions {
		allWarningsAsErrors = true
		// Resolving a reference or an index expression to its symbol is still marked experimental in the 2.4 Analysis
		// API and in the PSI under it
		optIn.addAll("org.jetbrains.kotlin.analysis.api.KaExperimentalApi", "org.jetbrains.kotlin.psi.KtExperimentalApi")
	}
}

// The Analysis API and the IntelliJ platform modules it runs on are published only to JetBrains' repositories, so they
// are bundled into this module's jar, with the libraries they need: a plugin author's build then resolves Oneiro from
// Maven Central alone. They are compiled against, kept out of the published POM, and merged into the jar unchanged. The
// jar only ever runs in a worker process of its own, so their classes keep their names.
val bundled = configurations.dependencyScope("bundled")
val bundledClasspath = configurations.resolvable("bundledClasspath") {
	extendsFrom(bundled)
	// The standard library is an ordinary dependency, at the version the Kotlin plugin gives this module
	exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}
configurations.compileOnly {
	extendsFrom(bundled)
}

dependencies {
	api(project(":oneiro-lowering"))
	// The compiler's settings, which the build writes for the worker that runs this front end
	implementation(project(":oneiro-plugin-model"))
	// For constants such as LuaConstants.PREFIX, which the compiler copies into the code that reads them; the front
	// end finds the annotations themselves by name, in the plugin's own classpath
	compileOnly(project(":oneiro-api"))
	"bundled"(libs.bundles.intellij.platform)
	libs.bundles.kotlin.analysis.api.get().forEach {
		"bundled"("${it.module}:${it.version}") {
			isTransitive = false
		}
	}
	"bundled"(libs.caffeine)
	"bundled"(libs.kotlinx.collections.immutable)
}

/**
 * The notices that go with the bundled libraries: each library's own licence and notice files, under its group and
 * name; the licence texts kept by hand in `third-party/` for libraries that ship none; and a list of what is bundled.
 * A library without a folder of its own is under the Apache License 2.0, a copy of which is `Apache-2.0.txt`. When the
 * bundled libraries change, check that this still holds for each one that ships no licence files.
 */
abstract class BundledNotices : DefaultTask() {

	/** The bundled libraries, as `group:name:version`, in the order of [jars]. */
	@get:Input
	abstract val libraries: ListProperty<String>

	@get:InputFiles
	@get:PathSensitive(PathSensitivity.NONE)
	abstract val jars: ConfigurableFileCollection

	@get:InputDirectory
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val handKept: DirectoryProperty

	@get:OutputDirectory
	abstract val output: DirectoryProperty

	@TaskAction
	fun write() {
		val out = output.get().asFile
		out.deleteRecursively()
		handKept.get().asFile.copyRecursively(out)
		val notice = Regex("""META-INF/(LICENSE|NOTICE|COPYING|AL2\.0|LGPL2\.1)[^/]*""")
		libraries.get().zip(jars.files.toList()).forEach { (library, jar) ->
			val (group, name) = library.split(":")
			ZipFile(jar).use { zip ->
				zip.entries().asSequence().filter { notice.matches(it.name) }.forEach { entry ->
					val target = out.resolve("$group/$name/${entry.name.removePrefix("META-INF/")}")
					target.parentFile.mkdirs()
					zip.getInputStream(entry).use { input -> target.outputStream().use(input::copyTo) }
				}
			}
		}
		out.resolve("README.txt").writeText(
			"Oneiro's Kotlin front end bundles these libraries, unchanged, because some of them are published only to\n" +
				"JetBrains' repositories. Each library's own licence and notice files are in a folder named after its group\n" +
				"and name; a library without a folder is under the Apache License 2.0, a copy of which is Apache-2.0.txt.\n\n" +
				libraries.get().joinToString("\n") + "\n")
	}
}

val bundledNotices = tasks.register<BundledNotices>("bundledNotices") {
	val artifacts = bundledClasspath.flatMap { it.incoming.artifacts.resolvedArtifacts }
	libraries = artifacts.map { all -> all.map { it.id.componentIdentifier.displayName } }
	jars.from(artifacts.map { all -> all.map { it.file } })
	handKept = layout.projectDirectory.dir("third-party")
	output = layout.buildDirectory.dir("bundled-notices")
}

// Build scripts reach Gradle's services through an injected object
interface Services {
	@get:Inject
	val archives: ArchiveOperations
}

val archives = objects.newInstance<Services>().archives
tasks.jar {
	// The bundled jars after the module's own classes, in classpath order; where two hold the same file, the first wins,
	// as it does on a classpath. Their manifests, signatures, module descriptors and IntelliJ's class loader indexes
	// describe the jars they came from, and their notices go under META-INF/third-party.
	val zips = archives
	from(bundledClasspath.flatMap { it.elements }.map { jars -> jars.map { jar -> zips.zipTree(jar.asFile) } }) {
		exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
		exclude("**/module-info.class", "__index__")
		exclude("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/COPYING*", "META-INF/AL2.0", "META-INF/LGPL2.1")
	}
	from(bundledNotices) {
		into("META-INF/third-party")
	}
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE
	// Two bundled libraries hold classes for newer JDKs, which a multi-release jar picks as their own jars did
	manifest {
		attributes("Multi-Release" to "true")
	}
}
