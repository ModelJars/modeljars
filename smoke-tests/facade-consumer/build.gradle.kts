plugins {
    application
}

val modeljarsVersion =
    providers.gradleProperty("modeljarsVersion").orElse("0.1.0-SNAPSHOT")

dependencies {
    implementation("org.modeljars:modeljars:${modeljarsVersion.get()}")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "org.modeljars.smoke.FacadeConsumer"
}
