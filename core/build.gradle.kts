import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM module: all domain logic lives here so it can be unit-tested
// on any machine without an Android device or emulator (M1 Section 4.4 domain layer).
// It has no third-party runtime dependencies and no networking code.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}

// Criteria 4 and 5: run against the compiled KB (python kb/build_kb.py first).
tasks.register<JavaExec>("evaluate") {
    group = "verification"
    description = "Measures normalisation accuracy and interaction-detection recall (M1 criteria 4 and 5)."
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.airgaprx.core.eval.EvaluationKt")
    args(rootProject.projectDir.absolutePath)
}
