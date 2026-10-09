import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.airgaprx.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.airgaprx.app"
        minSdk = 29 // Android 10 (M1 Section 6.4)
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Debug builds may load the development knowledge base (clearly labelled in the UI).
        buildConfigField("boolean", "REQUIRE_RELEASE_KB", "false")
    }

    buildTypes {
        release {
            // Release builds refuse any KB that did not pass `build_kb.py --release` (signed metadata).
            buildConfigField("boolean", "REQUIRE_RELEASE_KB", "true")
            isMinifyEnabled = false
            // Signed with the debug key so the release APK can be sideloaded for marking/testing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // Keep the KB package and model files uncompressed inside the APK.
        noCompress += listOf("zip", "task", "sqlite")
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    // Capture + on-device OCR. The *bundled* ML Kit model is used deliberately: the unbundled
    // Play Services variant downloads its model over the network, which R1 forbids.
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.text.recognition)

    // Encrypted medication shelf (R8).
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)

    // Optional on-device language model for label parsing (loaded from a local file, never downloaded).
    implementation(libs.mediapipe.tasks.genai)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

/**
 * Evaluation criterion 1 (static half): fail the build if any network permission survives
 * manifest merging. Third-party libraries (ML Kit's datatransport/firebase dependencies) add
 * INTERNET transitively, so this must be checked on the *merged* manifest, not the source one.
 */
val networkPermissions = listOf(
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.CHANGE_NETWORK_STATE",
    "android.permission.CHANGE_WIFI_STATE",
)

listOf("Debug", "Release").forEach { variant ->
    val task = tasks.register("verifyNoNetworkPermission$variant") {
        group = "verification"
        description = "Fails if the merged $variant manifest declares any network permission (R1)."
        dependsOn("process${variant}Manifest")
        val intermediates = layout.buildDirectory.dir("intermediates")
        val v = variant.lowercase()
        doLast {
            // AGP versions differ in folder naming (merged_manifest / merged_manifests), so search both.
            val manifests = intermediates.get().asFileTree.matching {
                include("merged_manifest/$v/**/AndroidManifest.xml", "merged_manifests/$v/**/AndroidManifest.xml")
            }.files
            if (manifests.isEmpty()) throw GradleException("Merged $variant manifest not found under ${intermediates.get()}")
            manifests.forEach { file ->
                val text = file.readText()
                networkPermissions.filter { text.contains("\"$it\"") }.forEach {
                    throw GradleException("R1 violated: $it present in merged manifest $file")
                }
            }
            println("R1 OK: merged $variant manifest declares no network permission")
        }
    }
    tasks.matching { it.name == "assemble$variant" }.configureEach { dependsOn(task) }
}

/**
 * Release gate (build time): refuse to package a release APK around a development or unreviewed
 * knowledge base. The app enforces the same rule at runtime from the *signed* manifest, so editing
 * this check away still yields an APK that refuses to load development data.
 */
val verifyBundledKbIsRelease = tasks.register("verifyBundledKbIsRelease") {
    group = "verification"
    description = "Fails unless the bundled KB was built with build_kb.py --release."
    val zip = layout.projectDirectory.file("src/main/assets/kb/airgaprx-kb.zip")
    doLast {
        val props = java.util.Properties()
        java.util.zip.ZipFile(zip.asFile).use { z ->
            val entry = z.getEntry("kb.manifest.properties") ?: throw GradleException("KB package has no manifest")
            z.getInputStream(entry).use { props.load(it) }
        }
        val status = props.getProperty("data_status", "development")
        val unreviewed = props.getProperty("unreviewed_rows")?.toIntOrNull() ?: Int.MAX_VALUE
        val ddi = props.getProperty("ddi_source", "unknown")
        if (status != "release" || unreviewed != 0 || ddi != "DDInter") {
            throw GradleException(
                "Release blocked: bundled KB ${props.getProperty("release_id")} is $status data " +
                    "(unreviewed rows: $unreviewed, drug-drug source: $ddi). Run python kb/build_kb.py --release."
            )
        }
        println("KB release gate OK: ${props.getProperty("release_id")}")
    }
}
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach { dependsOn(verifyBundledKbIsRelease) }
