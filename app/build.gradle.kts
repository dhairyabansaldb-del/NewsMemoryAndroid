import groovy.json.JsonSlurper
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Groq API key lives in local.properties (gitignored), per EDD §6.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.dhairya.newsmemory"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dhairya.newsmemory"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "GROQ_API_KEY",
            "\"${localProps.getProperty("GROQ_API_KEY", "")}\""
        )
    }

    buildTypes {
        release {
            // Personal sideloaded APK; minification adds risk without benefit here.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

/**
 * Generates SharedConfig.kt from shared/pipeline-config.json + the prompt file, so the
 * app and the offline eval harness (tools/eval_clustering.py) read ONE source of truth.
 * Generating at build time keeps these compile-time constants — no runtime parsing on
 * the digest path — while the harness reads the same JSON directly at run time.
 */
abstract class GenerateSharedConfig : DefaultTask() {
    @get:InputFile abstract val configJson: RegularFileProperty
    @get:InputFile abstract val promptFile: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    /** Escape for a normal Kotlin string literal ($ matters — Kotlin interpolates). */
    private fun esc(s: String): String = s
        .replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("$", "\\$").replace("\n", "\\n")

    @TaskAction
    fun generate() {
        @Suppress("UNCHECKED_CAST")
        val cfg = JsonSlurper().parse(configJson.get().asFile) as Map<String, Any>
        val prompt = promptFile.get().asFile.readText().trimEnd()
        val stopwords = (cfg["stopwords"] as List<*>).joinToString(", ") { "\"$it\"" }

        val dir = outputDir.get().asFile.resolve("com/dhairya/newsmemory")
        dir.mkdirs()
        dir.resolve("SharedConfig.kt").writeText(
            """
            package com.dhairya.newsmemory

            // GENERATED at build time — DO NOT EDIT.
            // Source: shared/pipeline-config.json + shared/${cfg["promptFile"]}
            // Edit those instead; the eval harness reads the same files.
            object SharedConfig {
                const val PROMPT_VERSION = "${cfg["promptVersion"]}"
                const val CLUSTERING_MODEL = "${cfg["clusteringModel"]}"
                const val REASONING_EFFORT = "${cfg["reasoningEffort"]}"
                const val TPM_BUDGET = ${cfg["tpmBudget"]}
                const val MIN_COMPLETION_TOKENS = ${cfg["minCompletionTokens"]}
                const val DEFAULT_BODY_CHARS = ${cfg["defaultBodyChars"]}
                const val JACCARD_THRESHOLD = ${cfg["jaccardThreshold"]}
                val STOPWORDS: Set<String> = setOf($stopwords)
                const val CLUSTERING_SYSTEM_PROMPT: String = "${esc(prompt)}"
            }
            """.trimIndent() + "\n"
        )
    }
}

val generateSharedConfig = tasks.register<GenerateSharedConfig>("generateSharedConfig") {
    configJson.set(rootProject.file("shared/pipeline-config.json"))
    promptFile.set(rootProject.file("shared/clustering-prompt-v2.txt"))
    outputDir.set(layout.buildDirectory.dir("generated/sharedconfig"))
}

android.sourceSets.getByName("main").kotlin.srcDir(generateSharedConfig)

// KSP compiles the whole source set, so it needs the generated file too.
tasks.matching { it.name.startsWith("ksp") }.configureEach { dependsOn(generateSharedConfig) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
