plugins {
    id("buildsrc.convention.published-module")
}

dependencies {
    api(project(":vibris-capture"))
    implementation(libs.kotlinxSerializationCore)

    testImplementation(kotlin("test"))
}

sourceSets.main {
    resources.srcDir("src/main/python")
}

val pythonTest by tasks.registering(Exec::class) {
    commandLine("py", "-3", "-m", "unittest", "src/test/python/test_vibris_capture_mcp.py")
}

tasks.check {
    dependsOn(pythonTest)
}