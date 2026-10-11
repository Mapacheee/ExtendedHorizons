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
    maven("https://oss.sonatype.org/content/groups/public/")
    maven("https://repo.faststats.dev/releases")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://repo.codemc.org/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-snapshots/")
    maven("https://maven.enginehub.org/repo/")
}

dependencies {
    // MapacheeeLib & Paper
    compileOnlyApi(libs.mapacheeelib)
    annotationProcessor(libs.mapacheeelib)
    paperweight.paperDevBundle(libs.versions.paper.dev.bundle.get())

    // Hooks & Metrics
    compileOnly(libs.faststats.bukkit)
    compileOnly(libs.placeholderapi)
    compileOnly(platform(libs.intellectualsites.bom))
    compileOnly(libs.fawe.core)
    compileOnly(libs.fawe.bukkit) {
        isTransitive = false
    }

    // Testing
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.mapacheeelib)
    testImplementation(platform(libs.intellectualsites.bom))
    testImplementation(libs.fawe.core) {
        exclude(group = "org.yaml", module = "snakeyaml")
    }
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
        filteringCharset = "UTF-8"
        filesMatching(listOf("plugin.yml", "paper-plugin.yml")) {
            expand(mapOf("version" to project.version, "description" to project.description))
        }
    }
    shadowJar {
        relocate("com.github.benmanes.caffeine", "me.mapacheee.lib.caffeine")
        relocate("dev.faststats", "me.mapacheee.lib.faststats")
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
    test {
        useJUnitPlatform()
        workingDir = layout.buildDirectory.dir("test-work").get().asFile
        doFirst {
            workingDir.mkdirs()
        }
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
