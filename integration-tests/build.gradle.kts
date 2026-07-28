plugins {
    id("buildsrc.convention.java")
}

dependencies {
    testImplementation(project(":vibris-api"))
    testImplementation(project(":vibris-core"))
    testImplementation(project(":vibris-protocol-java"))
    testImplementation(project(":vibris-test-runtime"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    dependsOn(":vibris-protocol-java:jar", ":vibris-test-runtime:jar")
}