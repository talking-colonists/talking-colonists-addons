plugins {
	id("mod-platform")
	id("net.neoforged.moddev.legacyforge")
}

stonecutter {
	val (version, loader) = current.project.split('-', limit = 2)
	properties.tags(version, loader)

	replacements.string(current.parsed >= "1.21.11") {
		replace("ResourceLocation", "Identifier")
		replace("location()", "identifier()")
	}
}

var voicechat_version = "${property("deps.minecraft")}-${property("deps.voice_chat")}"

platform {
	loader = "forge"
	dependencies {
		required("minecraft") {
			forgeLikeVersionRange = prop("deps.minecraft")
		}
		required("forge") {
			forgeLikeVersionRange.set("[1,)")
		}
		required("mc_talking") {
			curseforge = "talking-colonists-minecolonies-addon"
			forgeLikeVersionRange = "[${prop("deps.talking_colonists_version")},)"
		}
	}
}

// The dev-only playtest mod (playtest/) runs every addon of this version in one client, in a
// ready-made world (scripts/playtest.sh). Other addons get an empty list.
val playtestAddons: List<Project> = if (project.parent?.name != "playtest") emptyList() else
	rootProject.subprojects.filter { it.name == project.name && it.parent?.parent == rootProject && it.parent?.name != "playtest" }
playtestAddons.forEach { evaluationDependsOn(it.path) }

legacyForge {
	version = "${prop("deps.minecraft")}-${prop("deps.forge")}"

	validateAccessTransformers = true

	accessTransformers.from(
		project.parent!!.file("src/main/resources/aw/${sc.current.version}.cfg")
	)

	runs {
		register("client") {
			client()
			gameDirectory = file("run/")
			ideName = "Forge Client (${sc.current.version})"
			programArgument("--username=Dev")
			// Straight into the playtest world once the playtest mod has created it.
			if (playtestAddons.isNotEmpty() && file("run/saves/TC_Playtest").isDirectory) {
				programArguments.addAll("--quickPlaySingleplayer", "TC_Playtest")
			}
			// Speech timeline for scripts/speech-report.sh (who spoke when, overlaps, repeats).
			if (playtestAddons.isNotEmpty()) systemProperty("mc_talking.speechTimeline", "true")
		}
		// Scripted headless playtest (scripts/scenario.sh): its own world, runs a scenario, quits.
		if (playtestAddons.isNotEmpty()) register("scenarioClient") {
			client()
			gameDirectory = file("run/scenario/")
			programArgument("--username=Dev")
			if (file("run/scenario/saves/TC_Playtest").isDirectory) {
				programArguments.addAll("--quickPlaySingleplayer", "TC_Playtest")
			}
			systemProperty("mc_talking.speechTimeline", "true")
			systemProperty("tc_playtest.scenario", providers.gradleProperty("tcScenario").getOrElse("campfire"))
		}
		register("server") {
			server()
			gameDirectory = file("run/")
			ideName = "Forge Server (${sc.current.version})"
		}
		// Dev-only end-to-end check (DevSelfTest): creates a colony, writes one issue, stops.
		register("selfTestServer") {
			server()
			gameDirectory = file("run/selftest/")
			programArgument("--nogui")
			systemProperty("${prop("mod.id")}.selftest", "true")
		}
	}


	mods {
		register(prop("mod.id")) {
			sourceSet(sourceSets["main"])
		}
		playtestAddons.forEach { addon ->
			register(addon.prop("mod.id")) {
				sourceSet(addon.sourceSets["main"])
			}
		}
	}
}

// The template has no mixins. When you add some, also add
//   mixin { add(sourceSets.main.get(), "${prop("mod.id")}.mixins.refmap.json"); config("${prop("mod.id")}.mixins.json") }
// and annotationProcessor("org.spongepowered:mixin:${libs.versions.mixin.get()}:processor"),
// otherwise the Forge build cannot remap them.

repositories {
    // Resolves a Talking Colonists build installed with `./gradlew publishToMavenLocal` first,
    // for testing against an unreleased version. Remove it if you do not need that.
    mavenLocal()
	mavenCentral()
	strictMaven("https://api.modrinth.com/maven", "maven.modrinth") { name = "Modrinth" }
	exclusiveContent {
		forRepository {
			maven {
				url = uri("https://cursemaven.com")
			}
		}
		filter {
			includeGroup("curse.maven")
		}
	}

	maven {
		name = "henkelmax.public"
		url = uri("https://maven.maxhenkel.de/repository/public")
	}

	maven {
		name = "LDTTeam - Mods Maven"
		url = uri("https://ldtteam.jfrog.io/ldtteam/mods-maven/")
	}

	maven {
		name = "Jared's maven"
		url = uri("https://maven.blamejared.com/")
	}

	maven {
		name = "ModMaven"
		url = uri("https://modmaven.dev")
	}
    maven {
        name = "Kotlin for Forge"
        url = uri("https://thedarkcolour.github.io/KotlinForForge/")
    }
    maven {
        name = "Xander Maven"
        url = uri("https://maven.isxander.dev/releases")
    }

	maven {
		name = "sshcrackRepositoryReleases"
		url = uri("https://maven.sshcrack.me/releases")
	}
}

dependencies {
    // Playtest only: the other addons' classes and resources.
    playtestAddons.forEach { runtimeOnly(it.sourceSets["main"].output) }
    testImplementation(platform("org.junit:junit-bom:5.14.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // Unit tests cover plain logic only and do not load Minecraft.
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.jetbrains:annotations:26.0.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Compile against the stable addon API only; the full mod is needed at runtime.
    compileOnly("me.sshcrack:mc_talking-api:${prop("deps.talking_colonists_version")}-${prop("deps.minecraft")}-forge")
    modRuntimeOnly("me.sshcrack:mc_talking:${prop("deps.talking_colonists_version")}-${prop("deps.minecraft")}-forge")
    // The API exposes MineColonies types such as AbstractEntityCitizen.
    modImplementation("com.ldtteam:minecolonies:${prop("deps.minecolonies_version")}")

    modRuntimeOnly("de.maxhenkel.voicechat:voicechat-api:${prop("deps.voicechat_api_version")}")
    modRuntimeOnly("maven.modrinth:simple-voice-chat:forge-${voicechat_version}")
    modRuntimeOnly("me.sshcrack:gemini_live_lib:${prop("deps.gemini_live_lib_version")}-${prop("deps.minecraft")}-forge")
    modRuntimeOnly("dev.isxander:yet-another-config-lib:${prop("deps.yacl_version")}+${prop("deps.minecraft")}-forge")
    modRuntimeOnly("com.ldtteam:domum_ornamentum:${prop("deps.domum_version")}:universal")
    // Compile-visible because MineColonies hut blocks extend Structurize types (the playtest places huts).
    modImplementation("com.ldtteam:structurize:${prop("deps.structurize_version")}")
    modRuntimeOnly("com.ldtteam:blockui:${prop("deps.blockui_version")}")
}

sourceSets {
	main {
		resources.srcDir(
			"${rootDir}/versions/datagen/${sc.current.version.split("-")[0]}/src/main/generated"
		)
	}
}

tasks.named("createMinecraftArtifacts") {
	dependsOn(tasks.named("stonecutterGenerate"))
}

tasks.test {
    useJUnitPlatform()
}

// The dev self-test never ships.
tasks.named<Jar>("jar") {
    exclude("${prop("mod.group").replace('.', '/')}/dev/**")
}
