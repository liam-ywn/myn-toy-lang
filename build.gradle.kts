plugins {
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(23)) // Use 21+ fine
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.ow2.asm:asm:9.7.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

application {
    mainClass.set("yewintnaing.dev.myn.Main")
}

tasks.test {
    useJUnitPlatform()
}
