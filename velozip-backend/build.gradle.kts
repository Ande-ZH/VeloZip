import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-library`
    alias(libs.plugins.shadow)
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    api(project(":velozip-common"))

    compileOnly(libs.findLibrary("paper-api").get())

    // snakeyaml is provided by the server (bukkit) at runtime.
    implementation(libs.findLibrary("hdrhistogram").get())

    testImplementation(project(":velozip-common"))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("VeloZip-Backend")
    archiveClassifier.set("")

    relocate("org.hdrhistogram", "dev.velozip.shaded.hdrhistogram")

    mergeServiceFiles()
}

tasks.jar {
    archiveClassifier.set("thin")
}
