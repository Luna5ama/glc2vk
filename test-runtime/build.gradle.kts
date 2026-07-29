plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    implementation(project(":vibris-core"))
    implementation(project(":vibris-protocol-java"))
    implementation("io.grpc:grpc-netty-shaded:${libs.versions.grpcJava.get()}")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar {
    dependsOn(configurations.runtimeClasspath)
    archiveFileName.set("vibris-test-runtime.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest.attributes["Main-Class"] = "dev.vibris.testruntime.FakeVibrisServerMain"
    exclude("META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.SF")
    from({
        configurations.runtimeClasspath.get().map(::zipTree)
    })
}

tasks.test {
    useJUnitPlatform()
}