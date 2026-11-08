plugins { kotlin("jvm"); id("com.google.protobuf") }
kotlin { jvmToolchain(21) }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
sourceSets { main { proto { srcDir("../schema") } } }
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.33.0" }
    generateProtoTasks { all().forEach { task -> task.builtins { named("java") { option("lite") } } } }
}
dependencies {
    api("com.google.protobuf:protobuf-javalite:4.33.0")
    api("com.google.code.gson:gson:2.13.2")
    testImplementation("junit:junit:4.13.2")
}
tasks.test { testLogging { events("passed", "skipped", "failed") } }
tasks.register<JavaExec>("synthetic") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.campusrecorder.core.SyntheticKt")
    args(rootProject.file("deliverables").absolutePath)
}
