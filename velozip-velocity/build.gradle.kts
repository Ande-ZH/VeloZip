import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-library`
    alias(libs.plugins.shadow)
}

val libs = the<VersionCatalogsExtension>().named("libs")

// Compile-only stubs of Velocity 3.4.0 proxy-internal classes (no maven artifact
// exists for them). Signatures are verified against commit 6b1ea78 — see
// docs/STUBS.md. They are never packaged into the jar; at runtime the plugin
// links against the real classes in the proxy.
sourceSets {
    create("stubs")
}

dependencies {
    "stubsCompileOnly"(project(":velozip-common"))
    "stubsCompileOnly"(libs.findLibrary("velocity-api").get())

    api(project(":velozip-common"))

    compileOnly(sourceSets["stubs"].output)
    compileOnly(libs.findLibrary("velocity-api").get())
    // velocity-plugin.json is written by hand; no annotation processor needed.

    // YAML config parsing (Velocity does not bundle snakeyaml).
    implementation(libs.findLibrary("snakeyaml").get())

    testImplementation(sourceSets["stubs"].output)
    testImplementation(libs.findLibrary("velocity-api").get())
    testImplementation(project(":velozip-common"))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("VeloZip-Velocity")
    archiveClassifier.set("")

    // zstd-jni MUST NOT be relocated: the JNI binding resolves the native
    // library through the exact class name (see zstd-jni README, "Limitations").
    relocate("org.yaml.snakeyaml", "dev.velozip.shaded.snakeyaml")
    relocate("org.hdrhistogram", "dev.velozip.shaded.hdrhistogram")

    mergeServiceFiles()

    // The stub classes must never leak into the shipped jar.
    exclude("com/velocitypowered/proxy/**")
}

// Keep the plain jar out of the way; the shadow jar is the deliverable.
tasks.jar {
    archiveClassifier.set("thin")
}
