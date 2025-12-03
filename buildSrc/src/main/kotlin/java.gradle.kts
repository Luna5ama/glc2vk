package buildsrc.convention

plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

val library: Configuration by configurations.creating {
    configurations.implementation.get().extendsFrom(this)
}
val projectLib: Configuration by configurations.creating {
    configurations.api.get().extendsFrom(this)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }
}