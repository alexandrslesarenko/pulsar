import com.android.build.api.artifact.ArtifactTransformationRequest
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifact
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// The release key, its passwords and the key rotation lineage live outside git, in
// ~/.gradle/gradle.properties (shared with Calorie). A clone without them builds with
// the debug key.
val signingProps = listOf(
    "releaseStoreFile", "releaseStorePassword", "releaseKeyAlias", "releaseKeyPassword",
    "releaseLineage",
).associateWith { providers.gradleProperty(it).orNull }
val hasReleaseKey = signingProps.values.all { it != null }
if (!hasReleaseKey && signingProps.values.any { it != null }) {
    throw GradleException(
        "Release signing: set all of ${signingProps.keys} or none, " +
            "missing ${signingProps.filterValues { it == null }.keys}"
    )
}
fun signingProp(name: String) = signingProps.getValue(name)!!

android {
    namespace = "com.puls.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.puls.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 3
        versionName = "1.1"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(signingProp("releaseStoreFile"))
                storePassword = signingProp("releaseStorePassword")
                keyAlias = signingProp("releaseKeyAlias")
                keyPassword = signingProp("releaseKeyPassword")
            }
        }
    }

    buildTypes {
        // Debug shares the release key, so installDebug goes over an installed release
        // and keeps the history.
        debug {
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    // Language list for the per-app language picker in Android 13+ settings.
    androidResources {
        generateLocaleConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

// Pulsar 1.0 was signed with the debug key. The lineage (apksigner rotate, debug key ->
// release key) lets an APK signed with the release key update it in place, and keeps
// the signature permission shared with Calorie granted to a Calorie still signed with
// the debug key. AGP cannot sign with a lineage, so apksigner signs the APK again.
abstract class SignWithLineage @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {
    @get:InputFiles abstract val apkDir: DirectoryProperty
    @get:OutputDirectory abstract val outDir: DirectoryProperty
    @get:Internal abstract val request: Property<ArtifactTransformationRequest<SignWithLineage>>
    @get:InputFile abstract val apksigner: RegularFileProperty
    @get:InputFile abstract val keyStore: RegularFileProperty
    @get:InputFile abstract val lineage: RegularFileProperty
    @get:Input abstract val keyAlias: Property<String>
    @get:Input abstract val minSdk: Property<Int>
    // Passwords reach apksigner through the environment, not the command line.
    @get:Internal abstract val storePassword: Property<String>
    @get:Internal abstract val keyPassword: Property<String>

    @TaskAction
    fun sign() {
        request.get().submit(this) { artifact: BuiltArtifact ->
            val input = File(artifact.outputFile)
            val output = outDir.file(input.name).get().asFile
            val tool = apksigner.get().asFile.path
            exec.exec {
                // v1 and v2 would have to be signed with the oldest key of the lineage;
                // v3 alone covers minSdk 31. Without --rotation-min-sdk-version apksigner
                // applies the new key only from Android 13 on.
                commandLine(
                    tool, "sign",
                    "--ks", keyStore.get().asFile.path, "--ks-key-alias", keyAlias.get(),
                    "--ks-pass", "env:SIGN_STORE_PASS", "--key-pass", "env:SIGN_KEY_PASS",
                    "--lineage", lineage.get().asFile.path,
                    "--rotation-min-sdk-version", minSdk.get().toString(),
                    "--v1-signing-enabled", "false", "--v2-signing-enabled", "false",
                    "--v3-signing-enabled", "true",
                    "--out", output.path, input.path,
                )
                environment("SIGN_STORE_PASS", storePassword.get())
                environment("SIGN_KEY_PASS", keyPassword.get())
            }
            exec.exec { commandLine(tool, "verify", output.path) }
            output
        }
    }
}

androidComponents {
    onVariants { variant ->
        if (!hasReleaseKey) return@onVariants
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val sign = tasks.register<SignWithLineage>("sign${name}ApkWithLineage") {
            apksigner.set(
                androidComponents.sdkComponents.sdkDirectory.map {
                    it.file("build-tools/${android.buildToolsVersion}/apksigner")
                }
            )
            keyStore.set(file(signingProp("releaseStoreFile")))
            lineage.set(file(signingProp("releaseLineage")))
            keyAlias.set(signingProp("releaseKeyAlias"))
            storePassword.set(signingProp("releaseStorePassword"))
            keyPassword.set(signingProp("releaseKeyPassword"))
            minSdk.set(variant.minSdk.apiLevel)
        }
        val request = variant.artifacts.use(sign)
            .wiredWithDirectories(SignWithLineage::apkDir, SignWithLineage::outDir)
            .toTransformMany(SingleArtifact.APK)
        sign.configure { this.request.set(request) }
    }
}

gradle.taskGraph.whenReady {
    if (!hasReleaseKey && allTasks.any { it.name == "packageRelease" }) {
        logger.warn("Release signing properties are not set: the release APK is signed with the debug key")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-service:2.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")
    implementation("androidx.health.connect:connect-client:1.1.0")

    testImplementation("junit:junit:4.13.2")
}
