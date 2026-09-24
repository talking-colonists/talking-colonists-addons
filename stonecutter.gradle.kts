@file:OptIn(dev.kikugie.stonecutter.StonecutterExperimentalAPI::class)

plugins {
	alias(libs.plugins.stonecutter)
	alias(libs.plugins.dotenv)
	alias(libs.plugins.fabric.loom).apply(false)
	alias(libs.plugins.fabric.loom.remap).apply(false)
	alias(libs.plugins.neoforged.moddev).apply(false)
	alias(libs.plugins.jsonlang.postprocess).apply(false)
	alias(libs.plugins.mod.publish.plugin).apply(false)
	alias(libs.plugins.kotlin.jvm).apply(false)
	alias(libs.plugins.devtools.ksp).apply(false)
	alias(libs.plugins.fletching.table).apply(false)
	alias(libs.plugins.legacyforge.moddev).apply(false)
}

stonecutter active file(".sc_active_version")

for (version in stonecutter.versions.map { it.version }.distinct()) tasks.register("publish$version") {
	group = "publishing"
	dependsOn(stonecutter.tasks.named("publishMods") { metadata.version == version })
}

stonecutter tasks {
	val ordering = versionComparator.thenComparingInt { task ->
		if (task.metadata.project.endsWith("fabric")) 1 else 0
	}

	listOf("publishModrinth", "publishCurseforge").forEach { taskName ->
		gradle.allprojects {
			if (project.tasks.findByName(taskName) != null) {
				order(taskName, ordering)
			}
		}
	}
}

tasks.register("runActiveClient") {
	group = "stonecutter"
	description = "Run client of the active Stonecutter version"
	dependsOn(stonecutter.current!!.project + ":runClient")
}

tasks.register("runActiveServer") {
	group = "stonecutter"
	description = "Run server of the active Stonecutter version"
	dependsOn(stonecutter.current!!.project + ":runServer")
}

stonecutter parameters {
	// Read the addon's own stonecutter.properties.toml (each branch has one).
	val props = node.project.extensions.getByType<dev.kikugie.stonecutter.build.StonecutterBuildExtension>().properties
	constants.match(node.metadata.project.substringAfterLast('-'), "fabric", "neoforge", "forge")
	filters.include("**/*.fsh", "**/*.vsh")
	swaps["mod_version"] = "\"${props.get<String>("mod.version")}\";"
	swaps["mod_id"] = "\"${props.get<String>("mod.id")}\";"
	swaps["mod_name"] = "\"${props.get<String>("mod.name")}\";"
	swaps["mod_group"] = "\"${props.get<String>("mod.group")}\";"
	swaps["minecraft"] = "\"${node.metadata.version}\";"
	constants["release"] = props.get<String>("mod.id") != "modtemplate"
}


subprojects {
    tasks.matching { it.name.startsWith("publish") }.configureEach {
        doFirst {
            val libraryDir = file("../talking-colonists")
            val confirmed = project.findProperty("colonistsPublished") == "true"
                || System.getenv("COLONISTS_PUBLISHED") == "true"

            if (libraryDir.exists() && !confirmed) {
                throw GradleException(
                    "talking-colonists is still included locally! " +
                        "Either publish it first, or confirm with -PcolonistsPublished=true"
                )
            }
        }
    }
}

// Shared code lives once in shared/src/{main,test}/java/me/sshcrack/tc_shared/<package>/ and is copied
// into every addon that uses it, under that addon's own package (<mod group>.shared.<package>): two mods
// that shipped the same package could not be loaded together. Edit the shared/ copy, then run
// `./gradlew syncShared`; `verifyShared` (run by CI) fails when a copy is out of date.
val sharedPackages = mapOf(
	"gazette" to listOf("book", "delivery", "provider", "store"),
	"postal" to listOf("book", "delivery", "provider", "store"),
	"tavern" to listOf("store"),
	"noticeboard" to listOf("book", "net", "provider", "store"),
	"townhall" to listOf("book", "delivery", "net", "provider", "store"),
	"playtest" to listOf("book"),
)
val sharedHeader = "// GENERATED from shared/: edit it there, then run ./gradlew syncShared\n"

fun sharedCopies(): Map<File, String> {
	val copies = mutableMapOf<File, String>()
	for ((addon, packages) in sharedPackages) {
		val group = Regex("""(?ms)^\[$addon\].*?^mod\.group\s*=\s*"([^"]+)"""")
			.find(file("stonecutter.properties.toml").readText())?.groupValues?.get(1)
			?: throw GradleException("No mod.group in the [$addon] table of stonecutter.properties.toml")
		for (sourceSet in listOf("main", "test")) {
			for (pkg in packages) {
				val from = file("shared/src/$sourceSet/java/me/sshcrack/tc_shared/$pkg")
				if (!from.isDirectory) continue
				val into = file("$addon/src/$sourceSet/java/${group.replace('.', '/')}/shared/$pkg")
				from.walkTopDown().filter { it.isFile }.forEach { source ->
					val text = source.readText().replace("me.sshcrack.tc_shared", "$group.shared")
					copies[into.resolve(source.relativeTo(from))] = sharedHeader + text
				}
			}
		}
	}
	return copies
}

fun staleSharedFiles(copies: Map<File, String>): List<File> =
	copies.keys.flatMap { it.parentFile.walkTopDown().filter { file -> file.isFile }.toList() }
		.distinct().filter { it !in copies && it.readText().startsWith(sharedHeader) }

tasks.register("syncShared") {
	group = "build"
	description = "Copies shared/ code into the addons that use it."
	doLast {
		val copies = sharedCopies()
		staleSharedFiles(copies).forEach { it.delete() }
		copies.forEach { (target, text) ->
			target.parentFile.mkdirs()
			if (!target.isFile || target.readText() != text) target.writeText(text)
		}
	}
}

tasks.register("verifyShared") {
	group = "verification"
	description = "Fails when an addon's copy of shared/ code is out of date."
	doLast {
		val copies = sharedCopies()
		val wrong = copies.filter { (target, text) -> !target.isFile || target.readText() != text }.keys + staleSharedFiles(copies)
		if (wrong.isNotEmpty()) {
			throw GradleException("Shared code copies are out of date; run ./gradlew syncShared:\n" +
				wrong.joinToString("\n") { "  " + it.relativeTo(rootDir) })
		}
	}
}

// Stonecutter must see the synced copies when both run in one build.
subprojects {
	tasks.matching { it.name == "stonecutterGenerate" }.configureEach {
		mustRunAfter(rootProject.tasks.named("syncShared"))
	}
}
