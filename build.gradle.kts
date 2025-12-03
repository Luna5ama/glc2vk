allprojects {
    group = "dev.luna5ama"
    version = "0.0.1-SNAPSHOT"
}

subprojects {
    repositories {
        mavenCentral()
        maven("https://maven.luna5ama.dev")
    }
}