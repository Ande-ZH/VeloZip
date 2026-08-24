plugins {
    `java-library`
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    api(project(":velozip-common"))
    implementation(libs.findLibrary("netty-handler").get())
    implementation(libs.findLibrary("netty-transport").get())
    implementation(libs.findLibrary("jmh-core").get())
    annotationProcessor(libs.findLibrary("jmh-annprocess").get())
}

// JMH benchmarks are run via the jmh task, not shipped.
tasks.register<JavaExec>("jmh") {
    group = "benchmark"
    description = "Run JMH benchmarks. Pass profiles with -PjmhArgs or --args."
    mainClass.set("org.openjdk.jmh.Main")
    classpath = sourceSets["main"].runtimeClasspath
    if (project.hasProperty("jmhArgs")) {
        args((project.property("jmhArgs") as String).split(" "))
    }
}