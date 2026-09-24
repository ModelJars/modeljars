
// Prints the runtime classpath so a bench host can be given exactly the dependency closure the
// composite needs, without reconstructing it by hand.
tasks.register("printRuntimeClasspath") {
    val files = configurations.named("runtimeClasspath")
    doLast { println(files.get().asPath) }
}
