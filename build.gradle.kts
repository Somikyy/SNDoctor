import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
    java
}

group = "network.somikyy"
version = "26.9.0"
description = "Checks every plugin in your plugins folder for Minecraft 26.x compatibility"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // The ONLY dependency, and it is compile-only: the server supplies these classes.
    // SNDoctor ships zero runtime dependencies on purpose - it has to be droppable onto a
    // server that is already broken, without shading anything into that server's classpath.
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
}

// paper-api 1.21.4 ships Java 21 class files (major version 65). Gradle will not put a Java 21
// library on a classpath it believes targets Java 17, and `options.release` below is exactly
// what tells it we target 17 - so raising the toolchain alone does not help, the release flag
// re-declares 17 and resolution fails again with "looking for a library compatible with JVM
// runtime version 17".
//
// The two requirements are not actually in conflict. javac reads Java 21 class files off the
// classpath perfectly well while emitting Java 17 bytecode; only Gradle's variant matching
// objects. paper-api is compileOnly and never reaches anyone's runtime, so what the compile
// classpath is allowed to contain and what we emit are separate questions. Say so.
configurations.named("compileClasspath") {
    attributes {
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
    }
}

java {
    // The COMPILER is 21, the OUTPUT is 17 - see options.release below.
    //
    // paper-api 1.21.4 is published for Java 21, and Gradle's variant-aware resolution refuses
    // to put a Java 21 library on a Java 17 compile classpath: "Dependency resolution is
    // looking for a library compatible with JVM runtime version 17". A 17 toolchain therefore
    // cannot resolve the dependency at all. Compiling with 21 and targeting 17 gets both:
    // the dependency resolves, and the class files still load on a Java 17 server.
    // .set() rather than `=`: assignment to a Property works only in newer Kotlin DSL, and a
    // build file that fails to parse on someone's Gradle is a support ticket for nothing.
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")

    // Java 17 bytecode rather than 21/25: SNDoctor must run on the old servers it is
    // diagnosing. Nothing in the scanner needs a newer language level. This is the same
    // `--release 17` the offline build passes to javac, so both builds emit the same thing.
    options.release.set(17)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "network.somikyy.sndoctor.cli.SNDoctorCli",
            "Implementation-Title" to "SNDoctor",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "Somikyy Network",
        )
    }
    archiveFileName.set("SNDoctor-${project.version}.jar")
}

// Convenience: gradle selftest  (needs bash and perl; runs the dependency-free fixture suite)
tasks.register<Exec>("selftest") {
    group = "verification"
    description = "Builds synthetic plugin jars and asserts every rule fires"
    commandLine("bash", "tools/offline/verify.sh")
}
