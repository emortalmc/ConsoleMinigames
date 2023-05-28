import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm") version "1.7.22"
    kotlin("plugin.serialization") version "1.7.22"
    id("com.github.johnrengelman.shadow") version "7.1.2"
    java
}

repositories {
    mavenCentral()
    mavenLocal()

    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.hollow-cube:Minestom:e6d4a2cc91")
//    implementation("dev.emortal.immortal:Immortal:3.0.1")
    implementation("com.github.EmortalMC:Immortal:bb0a38dc47")
    //compileOnly("com.github.EmortalMC:TNT:61bc234136")

    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")

//    implementation("com.github.EmortalMC:MinestomPvP:8f8741a0ce")
    implementation("io.github.bloepiloepi:MinestomPvP:1.0")
}

tasks {
    named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
        archiveBaseName.set(project.name)
        mergeServiceFiles()

        manifest {
            attributes (
                "Main-Class" to "dev.emortal.consoleminigames.ConsoleMinigamesMainKt",
                "Multi-Release" to true
            )
        }

        transform(com.github.jengelman.gradle.plugins.shadow.transformers.Log4j2PluginsCacheFileTransformer::class.java)
    }

    withType<AbstractArchiveTask> {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    build { dependsOn(shadowJar) }

}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions.jvmTarget = JavaVersion.VERSION_17.toString()

compileKotlin.kotlinOptions {
    freeCompilerArgs = listOf("-Xinline-classes")
}
