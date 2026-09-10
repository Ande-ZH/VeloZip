import java.util.zip.ZipFile

plugins {
    base
}

subprojects {
    apply(plugin = "java-library")

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    configure<org.gradle.api.plugins.JavaPluginExtension> {
        toolchain {
            // Explicit build JDK; all plugin bytecode targets Java 17.
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        // Includes Paper/Purpur 1.18 on Java 17.
        options.release.set(17)
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        providers.gradleProperty("testJavaVersion").orNull?.let { testJava ->
            javaLauncher.set(project.extensions.getByType<JavaToolchainService>().launcherFor {
                languageVersion.set(JavaLanguageVersion.of(testJava.toInt()))
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
                            check(major <= 61) { "Not Java 17 compatible: ${entry.name} (major $major)" }
                        }
                    }
                }
                val descriptor = if (module == "backend") "plugin.yml" else "velocity-plugin.json"
                val text = zip.getInputStream(zip.getEntry(descriptor)).bufferedReader().use { it.readText() }
                check(text.contains(if (module == "backend") "version: ${project.version}" else "\"version\": \"${project.version}\""))
                if (module == "backend") check(text.contains("api-version: '1.18'"))
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
