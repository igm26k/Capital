plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}
android {
    namespace = "com.capital.accounting"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.capital.accounting"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.11.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.08.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Local CA is public and debug-only; release resources always use system trust.
abstract class PrepareDebugTrust : DefaultTask() {
    @get:OutputDirectory abstract val outputDirectory: org.gradle.api.file.DirectoryProperty
    @get:InputFiles abstract val certificates: org.gradle.api.file.ConfigurableFileCollection
    @TaskAction fun generate() {
        val localCa = certificates.files.singleOrNull()
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.resolve("xml").mkdirs()
        val domains = if (localCa?.isFile == true) {
            output.resolve("raw").mkdirs()
            localCa.copyTo(output.resolve("raw/local_ca.crt"), overwrite = true)
            """<domain-config><domain>localhost</domain><domain>127.0.0.1</domain><trust-anchors><certificates src="system"/><certificates src="@raw/local_ca"/></trust-anchors></domain-config>"""
        } else ""
        output.resolve("xml/network_security_config.xml").writeText("""<network-security-config><base-config cleartextTrafficPermitted="false"><trust-anchors><certificates src="system"/></trust-anchors></base-config>$domains</network-security-config>""")
    }
}
val prepareDebugTrust = tasks.register<PrepareDebugTrust>("prepareDebugTrust") {
    certificates.from(rootProject.file("../ops/.runtime/tls/localhost.crt"))
    outputDirectory.set(layout.buildDirectory.dir("generated/debugTrust/res"))
}
androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) {
    it.sources.res?.addGeneratedSourceDirectory(prepareDebugTrust, PrepareDebugTrust::outputDirectory)
}
