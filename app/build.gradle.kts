import com.android.build.api.variant.FilterConfiguration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.konan.properties.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
}

val keystorePropertiesFile: File = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

fun hasSigningVars(): Boolean {
    return providers.environmentVariable("SIGNING_KEY_ALIAS").orNull != null
            && providers.environmentVariable("SIGNING_KEY_PASSWORD").orNull != null
            && providers.environmentVariable("SIGNING_STORE_FILE").orNull != null
            && providers.environmentVariable("SIGNING_STORE_PASSWORD").orNull != null
}

val baseVersionCode = project.property("VERSION_CODE").toString().toInt()
val abiCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 3, "x86_64" to 4)
val abiSplits = providers.gradleProperty("ABI_SPLITS").map(String::toBoolean).getOrElse(false)
val universalApk = providers.gradleProperty("UNIVERSAL_APK").map(String::toBoolean).getOrElse(true)
val selectedAbis = providers.gradleProperty("ABI_LIST")
    .map { list -> list.split(",").map(String::trim).filter { it in abiCodes } }
    .getOrElse(emptyList())
    .ifEmpty { abiCodes.keys.toList() }
val enableR8 = providers.gradleProperty("ENABLE_R8").map(String::toBoolean).getOrElse(true)
val shrinkResources = enableR8 && providers.gradleProperty("SHRINK_RESOURCES").map(String::toBoolean).getOrElse(true)
val crunchPngs = providers.gradleProperty("CRUNCH_PNGS").map(String::toBoolean).getOrElse(true)

base {
    archivesName = "gallery-$baseVersionCode"
}

android {
    compileSdk = project.libs.versions.app.build.compileSDKVersion.get().toInt()

    defaultConfig {
        applicationId = project.property("APP_ID").toString()
        minSdk = project.libs.versions.app.build.minimumSDK.get().toInt()
        targetSdk = project.libs.versions.app.build.targetSDK.get().toInt()
        versionName = project.property("VERSION_NAME").toString()
        versionCode = baseVersionCode
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            register("release") {
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
            }
        } else if (hasSigningVars()) {
            register("release") {
                keyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").get()
                storeFile = file(providers.environmentVariable("SIGNING_STORE_FILE").get())
                storePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").get()
            }
        } else {
            logger.warn("Warning: No signing config found. Build will be unsigned.")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = enableR8
            isShrinkResources = shrinkResources
            isCrunchPngs = crunchPngs
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists() || hasSigningVars()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    splits {
        abi {
            isEnable = abiSplits
            reset()
            include(*selectedAbis.toTypedArray())
            isUniversalApk = universalApk
        }
    }

    sourceSets {
        getByName("main").java.directories.add("src/main/kotlin")
    }

    compileOptions {
        val currentJavaVersionFromLibs =
            JavaVersion.valueOf(libs.versions.app.build.javaVersion.get())
        sourceCompatibility = currentJavaVersionFromLibs
        targetCompatibility = currentJavaVersionFromLibs
    }

    dependenciesInfo {
        includeInApk = false
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }

    tasks.withType<KotlinCompile> {
        compilerOptions.jvmTarget.set(
            JvmTarget.fromTarget(project.libs.versions.app.build.kotlinJVMTarget.get())
        )
    }

    namespace = "org.fossify.gallery"

    lint {
        checkReleaseBuilds = false
        abortOnError = true
        warningsAsErrors = false
        baseline = file("lint-baseline.xml")
        lintConfig = rootProject.file("lint.xml")
    }

    packaging {
        resources {
            excludes += "META-INF/library_release.kotlin_module"
        }
    }

    bundle {
        language {
            enableSplit = false
        }
    }
}

if (abiSplits) {
    androidComponents {
        onVariants(selector().withBuildType("release")) { variant ->
            variant.outputs.forEach { output ->
                val abi = output.filters.find {
                    it.filterType == FilterConfiguration.FilterType.ABI
                }?.identifier
                val versionCode = baseVersionCode + (abiCodes[abi] ?: 0)
                val abiSuffix = abi?.let { "-$it" }.orEmpty()
                val unsignedSuffix =
                    if (output.outputFileName.get().endsWith("-unsigned.apk")) "-unsigned" else ""
                output.versionCode.set(versionCode)
                output.outputFileName.set("gallery-$versionCode${abiSuffix}-release${unsignedSuffix}.apk")
            }
        }
    }
}

detekt {
    baseline = file("detekt-baseline.xml")
    config.setFrom("$rootDir/detekt.yml")
    buildUponDefaultConfig = true
    allRules = false
}

dependencies {
    implementation(libs.fossify.commons)
    implementation(libs.androidx.print)
    implementation(libs.android.image.cropper)
    implementation(libs.exif)
    implementation(libs.android.gif.drawable)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.sanselan)
    implementation(libs.androidphotofilters)
    implementation(libs.androidsvg.aar)
    implementation(libs.gestureviews)
    implementation(libs.subsamplingscaleimageview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.awebp)
    implementation(libs.apng)
    implementation(libs.avif)
    implementation(libs.avif.integration)
    implementation(libs.jxl.integration)
    implementation(libs.okio)
    implementation(libs.picasso) {
        exclude(group = "com.squareup.okhttp3", module = "okhttp")
    }
    compileOnly(libs.okhttp)

    ksp(libs.glide.compiler)
    implementation(libs.zjupure.webpdecoder)

    implementation(libs.bundles.room)
    ksp(libs.androidx.room.compiler)
    detektPlugins(libs.compose.detekt)
}
