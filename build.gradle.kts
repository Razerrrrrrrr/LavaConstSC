import java.io.ByteArrayOutputStream

plugins {
    `java-library`
    `maven-publish`
    id("dev.arbjerg.lavalink.gradle-plugin") version "1.0.15"
}

group = "dev.lavaconst"

// JitPack passes -Pversion=<tag>. Otherwise use the git tag at HEAD, or the short commit hash.
fun gitVersion(): String {
    fun git(vararg args: String): String? = try {
        val out = ByteArrayOutputStream()
        val result = project.exec {
            commandLine("git", *args)
            standardOutput = out
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }
        if (result.exitValue == 0) out.toString().trim().ifEmpty { null } else null
    } catch (e: Exception) {
        null
    }

    return git("describe", "--exact-match", "--tags") ?: git("rev-parse", "--short", "HEAD") ?: "dev"
}

if (project.version == "unspecified") {
    version = gitVersion()
}

repositories {
    mavenCentral()
    maven("https://maven.lavalink.dev/releases")
    maven("https://maven.arbjerg.dev/releases")
    maven("https://jitpack.io")
}

base {
    archivesName = "lavaconst-sc-plugin"
}

lavalinkPlugin {
    name = "lavaconst-sc-plugin"
    path = "dev.lavaconst.soundcloud.plugin"
    apiVersion = "4.0.0"
    serverVersion = "4.0.5"
    configurePublishing = false
}

dependencies {
    // Provided by Lavalink at runtime (Lavalink 4.2.2 bundles lavaplayer 2.2.6), so none of these are packaged.
    compileOnly("dev.arbjerg:lavaplayer:2.2.6")
    compileOnly("org.apache.httpcomponents:httpclient:4.5.14")
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    compileOnly("commons-io:commons-io:2.11.0")
    compileOnly("org.slf4j:slf4j-api:2.0.7")
    compileOnly("org.jetbrains:annotations:24.0.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = base.archivesName.get()
        }
    }
}
