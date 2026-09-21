rootProject.name = "VeloZip"

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        // Historical Spigot API snapshots are required for the 1.16 compile ABI.
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    }
}

include("velozip-common")
include("velozip-velocity")
include("velozip-backend")
include("velozip-benchmark")
include("velozip-itest")
