import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-library`
    alias(libs.plugins.shadow)
}

val libs = the<VersionCatalogsExtension>().named("libs")

// Compile-only stubs of paper-server / vanilla NMS classes (no maven artifact
// for paper-server; the vanilla jar is unobfuscated but not published as a
// compile dependency). Signatures verified against Purpur 26.1.2 — see
// docs/STUBS.md. Never packaged; runtime links against the real server classes.
sourceSets {
    create("stubs")
}

dependencies {
    "stubsCompileOnly"(project(":velozip-common"))
    "stubsCompileOnly"(libs.findLibrary("paper-api").get())

    api(project(":velozip-common"))

    compileOnly(sourceSets["stubs"].output)
    compileOnly(libs.findLibrary("paper-api").get())

    // snakeyaml is provided by the server (bukkit) at runtime.

    testImplementation(project(":velozip-common"))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    // Oldest candidate API is Java 21; runtime compatibility needs separate ABI/E2E evidence.
    options.release.set(21)
    options.encoding = "UTF-8"
}

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("VeloZip-Backend")
    archiveClassifier.set("")

    relocate("org.hdrhistogram", "dev.velozip.shaded.hdrhistogram")

    manifest.attributes["paperweight-mappings-namespace"] = "mojang"
    mergeServiceFiles()

    // The stub classes must never leak into the shipped jar.
    exclude("net/minecraft/**")
    exclude("org/bukkit/craftbukkit/**")
    exclude("io/papermc/paper/configuration/**")
}

// Keep the plain jar out of the way; the shadow jar is the deliverable.
tasks.jar {
    archiveClassifier.set("thin")
}
