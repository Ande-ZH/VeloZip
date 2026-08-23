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
    }
}

include("velozip-common")
include("velozip-velocity")
include("velozip-backend")
include("velozip-benchmark")
