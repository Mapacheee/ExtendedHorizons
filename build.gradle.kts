import org.gradle.api.tasks.compile.JavaCompile

plugins {
    java
    `java-library`
    alias(libs.plugins.shadow)
    alias(libs.plugins.paperweight.userdev)
    checkstyle
}

description = "ExtendedHorizons - Advanced view distance extension plugin"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    mavenLocal()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly(libs.mapacheeelib)
    compileOnly(libs.fawe.core) { isTransitive = false }
    compileOnly(libs.fawe.bukkit) { isTransitive = false }
    annotationProcessor(libs.winter.paper)
    annotationProcessor(libs.winter.command)
    annotationProcessor(libs.winter.configuration)
    paperweight.paperDevBundle(libs.versions.paper.dev.bundle.get())
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.add("-parameters")
}

tasks {
    jar {
        archiveClassifier.set("plain")
    }
    processResources {
        exclude("winter.properties")
        inputs.property("version", project.version)
        inputs.property("description", project.description)
        filteringCharset = "UTF-8"
        filesMatching("paper-plugin.yml") {
            expand(mapOf("version" to project.version, "description" to project.description))
        }
    }
    shadowJar {
        manifest {
            attributes(
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version,
                "Multi-Release" to "false"
            )
        }

        archiveBaseName.set("ExtendedHorizons")
        archiveClassifier.set("")
        archiveVersion.set(project.version.toString())

        configurations = listOf(project.configurations.runtimeClasspath.get())

        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    build {
        dependsOn(shadowJar)
    }
}

checkstyle {
    toolVersion = libs.versions.checkstyle.get()
    configFile = file("checkstyle.xml")
    isIgnoreFailures = true
}
