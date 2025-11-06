plugins {
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(23)) // Use 21+ fine
    }
}

dependencies { }

application {
    mainClass.set("yewintnaing.dev.myn.Main")
}

tasks.test {
    useJUnitPlatform()
}