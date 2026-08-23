plugins {
    `java-library`
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    api(libs.findLibrary("netty-codec").get())
    api(libs.findLibrary("zstd-jni").get())
    api(libs.findLibrary("hdrhistogram").get())

    testImplementation(libs.findLibrary("netty-handler").get())
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
