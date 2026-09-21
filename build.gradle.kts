import java.util.zip.ZipFile

plugins {
    base
}

subprojects {
    apply(plugin = "java-library")

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    }

    configure<org.gradle.api.plugins.JavaPluginExtension> {
        toolchain {
            // Explicit build JDK; release targets are set per module below.
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        // Backend/common support the 1.16.5 API baseline; proxy/tooling modules keep Java 17.
        val legacyCompatible = project.name == "velozip-common" || project.name == "velozip-backend"
        options.release.set(if (legacyCompatible) 16 else 17)
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        providers.gradleProperty("testJavaVersion").orNull?.let { testJava ->
            val requested = testJava.toInt()
            // Velocity's compile-only API and MCProtocolLib require Java 17+;
            // the legacy matrix runs common/backend tests on Java 16 and keeps
            // proxy/tooling tests on their minimum supported Java 17 runtime.
            val effective = if (requested == 16
                    && project.name != "velozip-common"
                    && project.name != "velozip-backend") 17 else requested
            javaLauncher.set(project.extensions.getByType<JavaToolchainService>().launcherFor {
                languageVersion.set(JavaLanguageVersion.of(effective))
            })
        }
        // Leak detection for all Netty-based tests.
        systemProperty("io.netty.leakDetection.level", "PARANOID")
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    // Exercise old backend and current proxy Netty ABIs.
    providers.gradleProperty("nettyTestVersion").orNull?.let { nettyVersion ->
        configurations.matching { it.name.startsWith("test") }.configureEach {
            resolutionStrategy.eachDependency {
                if (requested.group == "io.netty") useVersion(nettyVersion)
            }
        }
    }
}

// Inspect the actual deliverables, including shaded dependency bytecode.
val verifyArtifacts = tasks.register("verifyArtifacts") {
    group = "verification"
    dependsOn(":velozip-backend:shadowJar", ":velozip-velocity:shadowJar")
    doLast {
        for ((module, side) in listOf("backend" to "Backend", "velocity" to "Velocity")) {
            val artifact = project(":velozip-$module").layout.buildDirectory
                .file("libs/VeloZip-$side-${project.version}.jar").get().asFile
            ZipFile(artifact).use { zip ->
                for (entry in zip.entries().asSequence()) {
                    check(!entry.name.startsWith("io/netty/") && !entry.name.startsWith("net/minecraft/")
                            && !entry.name.startsWith("org/bukkit/") && !entry.name.startsWith("com/velocitypowered/")
                            && !entry.name.startsWith("io/papermc/") && !entry.name.startsWith("org/yaml/snakeyaml/")
                            && !entry.name.startsWith("org/HdrHistogram/")) {
                        "Platform classes or unisolated dependencies in $artifact: ${entry.name}"
                    }
                    if (entry.name.endsWith(".class") && !entry.name.startsWith("META-INF/versions/")) {
                        zip.getInputStream(entry).use { input ->
                            val header = input.readNBytes(8)
                            val major = ((header[6].toInt() and 255) shl 8) or (header[7].toInt() and 255)
                            val maximum = if (module == "backend") 60 else 61
                            check(major <= maximum) {
                                "Not Java ${if (module == "backend") 16 else 17} compatible: ${entry.name} (major $major)"
                            }
                        }
                    }
                }
                val descriptor = if (module == "backend") "plugin.yml" else "velocity-plugin.json"
                val text = zip.getInputStream(zip.getEntry(descriptor)).bufferedReader().use { it.readText() }
                check(text.contains(if (module == "backend") "version: ${project.version}" else "\"version\": \"${project.version}\""))
                if (module == "backend") check(text.contains("api-version: '1.16'"))
                val source = project(":velozip-$module").file("src/main/java/dev/velozip/$module/VeloZip${side}Plugin.java").readText()
                check(source.contains("PLUGIN_VERSION = \"${project.version}\"")) { "Plugin version constant drift" }
                check(zip.getEntry("com/github/luben/zstd/Zstd.class") != null) { "zstd JNI binding missing/relocated" }
                check(zip.getEntry("dev/velozip/shaded/snakeyaml/Yaml.class") != null)
                check(zip.getEntry("dev/velozip/shaded/hdrhistogram/Histogram.class") != null)
            }
        }
    }
}
tasks.named("check") { dependsOn(verifyArtifacts) }
