// android/quiet-spike/app/build.gradle.kts
//
// Single-module spike target. Reads quiet/.env at build time so secrets
// never live in version control; CI provides the same names from GitHub
// Actions secrets via a synthesised .env (see .github/workflows/ci.yml).
//
// Constraints (from quiet/CLAUDE.md):
// - Capture sheet: ≤0.8 s perceived t_keystroke, single-accent #1F3A5F,
//   no toast/animation/sound on commit, empty field is the receipt.
// - Schema source of truth is quiet/schema/capture.sql; the SQLDelight
//   .sq file is *generated* by the copyCaptureSchema task below — do not
//   hand-edit it.

import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

// ----------------------------------------------------------------------------
// .env loading. The file lives at the repo root (../../.env from this module).
// CI synthesises it from GitHub Actions secrets in the workflow, so the same
// code path serves local + CI. Never commit .env (already in .gitignore).
// ----------------------------------------------------------------------------
val envFile = rootProject.file("../../.env")
val env: Map<String, String> = if (envFile.exists()) {
    Properties().apply { FileInputStream(envFile).use(::load) }
        .entries.associate { it.key.toString() to it.value.toString() }
} else {
    logger.warn("[.env] ${envFile.absolutePath} not found — BuildConfig keys will be empty. " +
        "Sandbox auth + sync will fail at runtime; latency instrumentation still works.")
    emptyMap()
}
fun envOr(key: String, default: String = ""): String = env[key]?.takeIf { it.isNotBlank() } ?: default

android {
    namespace = "app.quiet.spike"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.quiet.spike"   // matches proto java_package family
        minSdk = 26                          // Pixel 6a runs 33+; 26 keeps share-intent work straightforward later
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-spike"

        // Surface .env values into BuildConfig so app code reads typed
        // constants. Strings are escaped so no special character breaks
        // the generated Java.
        buildConfigField("String", "SUPABASE_URL",       "\"${envOr("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY",  "\"${envOr("SUPABASE_ANON_KEY")}\"")
        buildConfigField("String", "POWERSYNC_URL",      "\"${envOr("POWERSYNC_URL")}\"")
        buildConfigField("String", "QUIET_TEST_USER_EMAIL",    "\"${envOr("QUIET_TEST_USER_EMAIL")}\"")
        buildConfigField("String", "QUIET_TEST_USER_PASSWORD", "\"${envOr("QUIET_TEST_USER_PASSWORD")}\"")
        buildConfigField("String", "QUIET_TEST_USER_ID",       "\"${envOr("QUIET_TEST_USER_ID")}\"")
    }

    buildTypes {
        // Debug-only spike — no release config until signing is real.
        debug {
            isMinifyEnabled = false
            // SQLite WAL mode + sync OFF would distort the t_local fsync
            // measurement. Leave default journal mode; the spike measures
            // production defaults, not optimisations.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/INDEX.LIST",
            "/META-INF/io.netty.versions.properties"
        )
    }
}

sqldelight {
    databases {
        create("CaptureDatabase") {
            packageName.set("app.quiet.spike.db")
            srcDirs.setFrom("src/main/sqldelight")
        }
    }
}

dependencies {
    // Compose (BOM-aligned)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Persistence
    implementation(libs.sqldelight.android.driver)
    implementation(libs.sqldelight.coroutines)

    // Sync
    implementation(libs.powersync.core)
    implementation(libs.powersync.connector.supabase)

    // Auth + Postgrest (Supabase Kotlin SDK)
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.gotrue)
    implementation(libs.supabase.postgrest)
    implementation(libs.ktor.client.cio)

    // Misc
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}

// ----------------------------------------------------------------------------
// Schema sync (DoD #2)
//
// schema/capture.sql is the source of truth. We *copy* it into the SQLDelight
// source set verbatim on every build, and a separate verifySchemaInSync task
// hashes both files so CI fails the moment they drift. Hand-editing the .sq
// is forbidden — the canonical file is at quiet/schema/capture.sql.
//
// Both tasks are configuration-cache safe: they declare RegularFileProperty
// inputs on a typed task class instead of capturing script-level vars in
// doFirst/doLast lambdas. Copy uses provider-based path resolution.
// ----------------------------------------------------------------------------
val captureSchemaSourceProvider = rootProject.layout.projectDirectory
    .file("../../schema/capture.sql")
val captureSchemaTargetProvider = layout.projectDirectory
    .file("src/main/sqldelight/app/quiet/spike/db/CaptureItems.sq")

val copyCaptureSchema by tasks.registering(Copy::class) {
    from(captureSchemaSourceProvider)
    into(captureSchemaTargetProvider.asFile.parentFile)
    rename { "CaptureItems.sq" }
}

abstract class VerifySchemaInSyncTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val source: RegularFileProperty

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val target: RegularFileProperty

    @TaskAction
    fun verify() {
        val srcFile = source.get().asFile
        val dstFile = target.get().asFile
        if (!dstFile.exists()) {
            throw GradleException(
                "[verifySchemaInSync] target missing: ${dstFile.absolutePath}. " +
                "Run :app:copyCaptureSchema first."
            )
        }
        val srcHash = sha256(srcFile)
        val dstHash = sha256(dstFile)
        if (srcHash != dstHash) {
            throw GradleException(
                "[verifySchemaInSync] DRIFT detected.\n" +
                "  source: ${srcFile.absolutePath}  sha256=$srcHash\n" +
                "  target: ${dstFile.absolutePath}  sha256=$dstHash\n" +
                "Fix: run :app:copyCaptureSchema and commit the result. " +
                "Never hand-edit the .sq file."
            )
        }
        logger.lifecycle("[verifySchemaInSync] OK ($srcHash)")
    }

    private fun sha256(f: java.io.File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { stream ->
            val buf = ByteArray(8192)
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

val verifySchemaInSync by tasks.registering(VerifySchemaInSyncTask::class) {
    description = "Fails if android/quiet-spike's CaptureItems.sq has drifted from schema/capture.sql."
    group = "verification"
    source.set(captureSchemaSourceProvider)
    target.set(captureSchemaTargetProvider)
    dependsOn(copyCaptureSchema)
}

// Run the copy before SQLDelight reads sources.
tasks.matching { it.name.startsWith("generate") && it.name.contains("Sqldelight", ignoreCase = true) }
    .configureEach { dependsOn(copyCaptureSchema) }
// Belt-and-braces: also bind to preBuild so a clean Compose-only build
// path doesn't skip the copy.
tasks.named("preBuild") { dependsOn(copyCaptureSchema) }
