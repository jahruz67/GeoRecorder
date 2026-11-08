plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "org.campusrecorder"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.campusrecorder"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    packaging { resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/*.kotlin_module") }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
val schemaAssets = layout.buildDirectory.dir("generated/recordingAssets")
val syncRecordingSchema by tasks.registering(Sync::class) {
    from(rootProject.file("schema/recording.proto"))
    into(schemaAssets.map { it.dir("schema") })
}
android.sourceSets.getByName("main").assets.srcDir(schemaAssets)
tasks.named("preBuild") { dependsOn(syncRecordingSchema) }
dependencies {
    implementation(project(":core"))
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
