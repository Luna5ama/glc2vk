plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    api(project(":vibris-api"))
    api(project(":vibris-protocol-java"))
    implementation("io.grpc:grpc-netty-shaded:${libs.versions.grpcJava.get()}")
    implementation(libs.kotlinxSerializationJson)

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}