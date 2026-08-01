plugins {
    java
}

group = "network.somikyy"
version = "1.0.0"
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

java {
    // Java 17 rather than 21/25: SNDoctor must run on the old servers it is diagnosing.
    // Nothing in the scanner needs a newer language level.
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
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
    archiveFileName = "SNDoctor-${project.version}.jar"
}

// Convenience: gradle selftest  (needs bash and perl; runs the dependency-free fixture suite)
tasks.register<Exec>("selftest") {
    group = "verification"
    description = "Builds synthetic plugin jars and asserts every rule fires"
    commandLine("bash", "tools/offline/verify.sh")
}
