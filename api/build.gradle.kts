import buildsrc.convention.transformMarkedRecordConstructors

plugins {
    id("buildsrc.convention.kotlin-jvm")
}

transformMarkedRecordConstructors()

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}