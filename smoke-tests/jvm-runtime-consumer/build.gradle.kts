import java.util.Properties

plugins {
    application
}

val modeljarsVersion =
    providers.gradleProperty("modeljarsVersion").orElse(
        providers.fileContents(layout.projectDirectory.file("../../gradle.properties")).asText.map {
            Properties().apply { load(it.reader()) }.getProperty("modeljarsVersion")
                ?: error("The repository must declare modeljarsVersion")
        },
    )

dependencies {
    implementation("org.modeljars:modeljars:${modeljarsVersion.get()}")
    implementation(
        "org.modeljars.huggingface:" +
            "ggml-org.qwen3-0.6b-gguf.q4_0:" +
            "3.0.0-q4_0.1",
    )
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

application {
    mainClass = "org.modeljars.smoke.JvmRuntimeConsumer"
}
