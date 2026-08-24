plugins {
    `java-library`
}

val libs = the<VersionCatalogsExtension>().named("libs")

repositories {
    mavenCentral()
    maven("https://repo.opencollab.dev/maven-releases/")
    maven("https://repo.opencollab.dev/main/")
}

dependencies {
    implementation("org.geysermc.mcprotocollib:protocol:26.1-1")
    implementation("org.slf4j:slf4j-simple:2.0.17")
}

// The E2E bot is run manually against a local Velocity+Purpur stack:
//   ./gradlew :velozip-itest:bot -PbotHost=127.0.0.1 -PbotPort=25565
tasks.register<JavaExec>("bot") {
    group = "verification"
    description = "Run the E2E login bot against a live Velocity endpoint."
    mainClass.set("dev.velozip.itest.BotMain")
    classpath = sourceSets["main"].runtimeClasspath
    systemProperty("bot.host", (project.findProperty("botHost") ?: "127.0.0.1").toString())
    systemProperty("bot.port", (project.findProperty("botPort") ?: "25565").toString())
    systemProperty("bot.seconds", (project.findProperty("botSeconds") ?: "20").toString())
}
