plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.jarOptimizer)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(24))
    }
}

repositories {
    mavenLocal()
}

dependencies {
    implementation(project(":glc2vk-common"))
    implementation(project(":glc2vk-capture"))

    implementation(libs.kotlinxSerializationCore)
    implementation(libs.fastutil)
    implementation(libs.kmogus.core)

    implementation(libs.caelum.core)
    implementation(libs.caelum.vulkan)
    implementation(libs.caelum.glfw.vulkan)
}

tasks {
    jar {
        manifest {
            attributes["Main-Class"] = "dev.luna5ama.glc2vk.replay.VKReplayKt"
        }
    }

    val fatJar by registering(Jar::class) {
        group = "build"

        from(jar.get().archiveFile.map { zipTree(it) })
        from(configurations.runtimeClasspath.get().elements.map { set ->
            set.map {
                if (it.asFile.isDirectory) it else zipTree(
                    it
                )
            }
        })

        manifest {
            attributes["Main-Class"] = "dev.luna5ama.glc2vk.replay.ReplayKt"
        }

        duplicatesStrategy = DuplicatesStrategy.INCLUDE

        archiveClassifier.set("fatjar")
    }

    val optimizeFatJar = jarOptimizer.register(
        fatJar,
        "dev.luna5ama.glc2vk",
        "dev.luna5ama.kmogus",
        "net.echonolix"
    )

    artifacts {
        archives(optimizeFatJar)
    }
}
