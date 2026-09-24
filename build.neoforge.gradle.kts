plugins {
    id("mod-platform")
    id("net.neoforged.moddev")
}

stonecutter {
    val (version, loader) = current.project.split('-', limit = 2)
    properties.tags(version, loader)

    replacements.string(current.parsed >= "1.21.11") {
        replace("ResourceLocation", "Identifier")
        replace("location()", "identifier()")
    }
}

platform {
    loader = "neoforge"
    dependencies {
        required("minecraft") {
            forgeLikeVersionRange = prop("deps.minecraft")
        }
        required("neoforge") {
            forgeLikeVersionRange.set("[1,)")
        }
		required("mc_talking") {
			curseforge = "talking-colonists-minecolonies-addon"
			forgeLikeVersionRange = "[${prop("deps.talking_colonists_version")},)"
		}
    }
}

neoForge {
    version = prop("deps.neoforge")
    accessTransformers.from(project.parent!!.file("src/main/resources/aw/${stonecutter.current.version}.cfg"))
    validateAccessTransformers = true

    if (hasProperty("deps.parchment")) parchment {
        val (mc, ver) = prop("deps.parchment").split(':')
        mappingsVersion = ver
        minecraftVersion = mc
    }

    runs {
        register("client") {
            client()
            gameDirectory = file("run/")
            ideName = "NeoForge Client (${stonecutter.current.version})"
            programArgument("--username=Dev")
        }
        register("server") {
            server()
            gameDirectory = file("run/")
            ideName = "NeoForge Server (${stonecutter.current.version})"
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
    }
    sourceSets["main"].resources.srcDir("${rootDir}/versions/datagen/${sc.current.version.split("-")[0]}/src/main/generated")
}

var voicechat_version = "${property("deps.minecraft")}-${property("deps.voice_chat")}"

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
    testImplementation(platform("org.junit:junit-bom:5.14.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // Unit tests cover plain logic only and do not load Minecraft.
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.jetbrains:annotations:26.0.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Compile against the stable addon API only; the full mod is needed at runtime.
    compileOnly("me.sshcrack:mc_talking-api:${prop("deps.talking_colonists_version")}-${prop("deps.minecraft")}-neoforge")
    runtimeOnly("me.sshcrack:mc_talking:${prop("deps.talking_colonists_version")}-${prop("deps.minecraft")}-neoforge")
    // The API exposes MineColonies types such as AbstractEntityCitizen.
    implementation("com.ldtteam:minecolonies:${prop("deps.minecolonies_version")}")

    runtimeOnly("de.maxhenkel.voicechat:voicechat-api:${prop("deps.voicechat_api_version")}")
    runtimeOnly("maven.modrinth:simple-voice-chat:neoforge-${voicechat_version}")
    runtimeOnly("me.sshcrack:gemini_live_lib:${prop("deps.gemini_live_lib_version")}-${prop("deps.minecraft")}-neoforge")
    runtimeOnly("dev.isxander:yet-another-config-lib:${prop("deps.yacl_version")}+${prop("deps.minecraft")}-neoforge")
    runtimeOnly("com.ldtteam:domum-ornamentum:${prop("deps.domum_version")}")
    runtimeOnly("com.ldtteam:structurize:${prop("deps.structurize_version")}")
    runtimeOnly("com.ldtteam:blockui:${prop("deps.blockui_version")}")
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
