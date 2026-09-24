pluginManagement {
	repositories {
		mavenLocal()
		mavenCentral()
		gradlePluginPortal()
		maven("https://maven.fabricmc.net/") { name = "Fabric" }
		maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
		maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
		maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
		maven("https://maven.parchmentmc.org") { name = "ParchmentMC" }
	}
	includeBuild("build-logic")
}

plugins {
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
	id("dev.kikugie.stonecutter") version "0.9.2"
}

stonecutter {
	create(rootProject) {
		// Every addon is a Stonecutter branch: its own directory with src/, and a table in stonecutter.properties.toml,
		// built for each loader by the shared build.<loader>.gradle.kts scripts at the repository root.
		// "playtest" is a dev-only mod that runs every addon together (scripts/playtest.sh); it is never released.
		for (addon in listOf("gazette", "campfire", "postal", "tavern", "noticeboard", "playtest")) branch(addon) {
			version("1.21.1-neoforge", "1.21.1").buildscript = "../build.neoforge.gradle.kts"
			version("1.20.1-forge", "1.20.1").buildscript = "../build.forge.gradle.kts"
		}

		vcsVersion = "1.21.1-neoforge"
	}
}
