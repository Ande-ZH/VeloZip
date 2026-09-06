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
            // Explicit build JDK; backend targets Java 21, common/proxy Java 17.
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        // Shipped bytecode stays Java 17 so both the Velocity JVM (>= 17)
        // and supported backend JVMs (21 or 25) can load the classes.
        options.release.set(17)
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Leak detection for all Netty-based tests.
        systemProperty("io.netty.leakDetection.level", "PARANOID")
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
