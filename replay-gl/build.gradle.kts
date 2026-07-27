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
    implementation(project(":vibris-common"))
    implementation(project(":vibris-capture"))


    implementation(platform("org.lwjgl:lwjgl-bom:${libs.versions.lwjgl.get()}"))
    implementation("org.lwjgl", "lwjgl")
    implementation("org.lwjgl", "lwjgl-opengl")
    implementation("org.lwjgl", "lwjgl-glfw")
    runtimeOnly("org.lwjgl", "lwjgl", classifier = "natives-windows")
    runtimeOnly("org.lwjgl", "lwjgl-opengl", classifier = "natives-windows")
    runtimeOnly("org.lwjgl", "lwjgl-glfw", classifier = "natives-windows")

    implementation(libs.kotlinxSerializationCore)
    implementation(libs.fastutil)
    implementation(libs.kmogus.core)
    implementation(libs.bundles.glWrapper)

    testImplementation(kotlin("test"))
}

tasks.test {
    systemProperty("vibris.runtimeTest", providers.gradleProperty("vibris.runtimeTest").orElse("false").get())
}

tasks {
    jar {
        manifest {
            attributes["Main-Class"] = "dev.luna5ama.vibris.replay.GLReplayKt"
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
            attributes["Main-Class"] = "dev.luna5ama.vibris.replay.GLReplayKt"
        }

        duplicatesStrategy = DuplicatesStrategy.INCLUDE

        archiveClassifier.set("fatjar")
    }

    val optimizeFatJar = jarOptimizer.register(
        fatJar,
        "dev.luna5ama.vibris",
        "dev.luna5ama.glwrapper",
        "dev.luna5ama.kmogus",
        "org.lwjgl"
    )

    artifacts {
        archives(optimizeFatJar)
    }
}
