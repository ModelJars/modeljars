plugins {
    `java-library`
    `maven-publish`
}

allprojects {
    group = "org.modeljars"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
        withJavadocJar()
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all", "-Werror"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    dependencies {
        testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
    }

    publishing {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                pom {
                    name.set(project.name)
                    description.set("ModelJars marker metadata for JVM model resolution")
                    url.set("https://modeljars.org")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                }
            }
        }
    }
}

project(":modeljars-core") {
    description = "Core ModelJars registry and locator APIs"

    dependencies {
        testRuntimeOnly(project(":modeljars-catalog-qwen3-0-6b-q4-0"))
        testRuntimeOnly(project(":modeljars-catalog-qwen2-5-coder-0-5b-instruct-q4-0"))
        testRuntimeOnly(project(":modeljars-catalog-qwen2-5-coder-0-5b-instruct-q8-0"))
        testRuntimeOnly(project(":modeljars-catalog-qwen2-5-coder-1-5b-instruct-q4-0"))
        testRuntimeOnly(project(":modeljars-catalog-qwen2-5-coder-1-5b-instruct-q8-0"))
    }
}

project(":modeljars-catalog-qwen3-0-6b-q4-0") {
    group = "org.modeljars.huggingface"
    version = "3.0.0-q4_0.1"
    description = "ModelJars marker for ggml-org/Qwen3-0.6B-GGUF Q4_0"

    publishing {
        publications.named<MavenPublication>("maven") {
            artifactId = "ggml-org.qwen3-0.6b-gguf.q4_0"
        }
    }
}

project(":modeljars-catalog-qwen2-5-coder-0-5b-instruct-q4-0") {
    group = "org.modeljars.huggingface"
    version = "2.5.0-q4_0.1"
    description = "ModelJars marker for Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF Q4_0"

    publishing {
        publications.named<MavenPublication>("maven") {
            artifactId = "qwen.qwen2.5-coder-0.5b-instruct-gguf.q4_0"
        }
    }
}

project(":modeljars-catalog-qwen2-5-coder-0-5b-instruct-q8-0") {
    group = "org.modeljars.huggingface"
    version = "2.5.0-q8_0.1"
    description = "ModelJars marker for Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF Q8_0"

    publishing {
        publications.named<MavenPublication>("maven") {
            artifactId = "qwen.qwen2.5-coder-0.5b-instruct-gguf.q8_0"
        }
    }
}

project(":modeljars-catalog-qwen2-5-coder-1-5b-instruct-q4-0") {
    group = "org.modeljars.huggingface"
    version = "2.5.0-q4_0.1"
    description = "ModelJars marker for Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF Q4_0"

    publishing {
        publications.named<MavenPublication>("maven") {
            artifactId = "qwen.qwen2.5-coder-1.5b-instruct-gguf.q4_0"
        }
    }
}

project(":modeljars-catalog-qwen2-5-coder-1-5b-instruct-q8-0") {
    group = "org.modeljars.huggingface"
    version = "2.5.0-q8_0.1"
    description = "ModelJars marker for Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF Q8_0"

    publishing {
        publications.named<MavenPublication>("maven") {
            artifactId = "qwen.qwen2.5-coder-1.5b-instruct-gguf.q8_0"
        }
    }
}
