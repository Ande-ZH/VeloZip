import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-library`
    alias(libs.plugins.shadow)
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    api(project(":velozip-common"))

    compileOnly(libs.findLibrary("paper-api").get())

    // Isolate YAML from the incompatible versions bundled by old servers.
    implementation(libs.findLibrary("snakeyaml").get())

    testImplementation(project(":velozip-common"))
    testImplementation(libs.findLibrary("paper-api").get())
    testImplementation(libs.findLibrary("netty-handler").get())
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("VeloZip-Backend")
    archiveClassifier.set("")

    relocate("org.HdrHistogram", "dev.velozip.shaded.hdrhistogram")
    relocate("org.yaml.snakeyaml", "dev.velozip.shaded.snakeyaml")

    dependencies { exclude(dependency("io.netty:.*")) }

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
