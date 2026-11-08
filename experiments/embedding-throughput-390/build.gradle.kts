plugins { application }
repositories { mavenCentral() }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
val sdkVersion = providers.gradleProperty("sdkVersion").getOrElse("0.1.76")
dependencies {
    implementation("org.modeljars:modeljars:$sdkVersion")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")
    runtimeOnly("org.modeljars.huggingface:ggml-org.embeddinggemma-2-gguf.q8_0:2.0.0-q8_0.1")
    runtimeOnly("org.modeljars.huggingface:ggml-org.embeddinggemma-300m-gguf.q8_0:3.0.0-q8_0.1")
}
application { mainClass.set("ReleasedEmbeddingConsumer") }
dependencyLocking {
    lockAllConfigurations()
    lockFile.set(file("locks/$sdkVersion.lockfile"))
}
