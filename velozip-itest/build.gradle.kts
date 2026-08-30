plugins {
    `java-library`
}

val libs = the<VersionCatalogsExtension>().named("libs")

repositories {
    mavenCentral()
    maven("https://repo.opencollab.dev/maven-releases/")
    maven("https://repo.opencollab.dev/main/")
}

// The legacy bot replaces MCProtocolLib 26.1-1 (protocol 775) with 1.21.11-1
// (protocol 774) to simulate a legacy client in the v0.2.0 E2E matrix (combo D).
// It deliberately does NOT extend the main implementation configuration: both
// versions in one classpath would resolve to the higher one.
val botLegacyRuntime = configurations.create("botLegacyRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    implementation("org.geysermc.mcprotocollib:protocol:26.1-1")
    implementation("org.slf4j:slf4j-simple:2.0.17")
    botLegacyRuntime("org.geysermc.mcprotocollib:protocol:1.21.11-1")
    botLegacyRuntime("org.slf4j:slf4j-simple:2.0.17")
}

fun registerBotTask(name: String, description: String, classpath: FileCollection) {
    tasks.register<JavaExec>(name) {
        group = "verification"
        this.description = description
        mainClass.set("dev.velozip.itest.BotMain")
        this.classpath = classpath
        systemProperty("bot.host", (project.findProperty("botHost") ?: "127.0.0.1").toString())
        systemProperty("bot.port", (project.findProperty("botPort") ?: "25565").toString())
        systemProperty("bot.seconds", (project.findProperty("botSeconds") ?: "20").toString())
    }
}

// The E2E bots are run manually against a local Velocity+Purpur stack:
//   ./gradlew :velozip-itest:bot -PbotHost=127.0.0.1 -PbotPort=25565
//   ./gradlew :velozip-itest:botLegacy -PbotHost=127.0.0.1 -PbotPort=25565
registerBotTask(
    "bot",
    "Run the E2E login bot (MC 26.1, protocol 775) against a live Velocity endpoint.",
    sourceSets["main"].runtimeClasspath
)
registerBotTask(
    "botLegacy",
    "Run the E2E login bot (MC 1.21.11, protocol 774) against a live Velocity endpoint.",
    sourceSets["main"].output + botLegacyRuntime
)