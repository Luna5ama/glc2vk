import com.google.protobuf.gradle.id

plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.protobuf)
}

dependencies {
    api(libs.grpc.protobuf)
    api(libs.grpc.stub)
    api(libs.protobuf.java)

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val descriptorResources = layout.buildDirectory.dir("generated/resources/protocolDescriptor")
val generateProtocolDescriptor by tasks.registering(JavaExec::class) {
    dependsOn(tasks.named("compileJava"))
    classpath = files(sourceSets.main.get().output.classesDirs, configurations.runtimeClasspath)
    mainClass.set("dev.vibris.protocol.DescriptorResourceWriter")
    outputs.dir(descriptorResources)
    args(descriptorResources.get().asFile.absolutePath)
}

sourceSets {
    main {
        resources.srcDir(generateProtocolDescriptor)
        proto {
            srcDir("../proto")
            include("vibris_control.proto")
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpcJava.get()}"
        }
    }
    generateProtoTasks {
        all().configureEach {
            plugins {
                id("grpc") {
                    option("@generated=omit")
                }
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveFileName.set("vibris-protocol-java.jar")
}