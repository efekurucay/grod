plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/** A Gradle property (-P or gradle.properties) or, for CI secrets, the environment variable of the same name. */
fun setting(name: String): String? = (findProperty(name) as String?) ?: System.getenv(name)

// CI passes the git tag; local builds keep the defaults.
val grodVersionName = setting("grodVersionName") ?: "0.1.0"
val grodVersionCode = setting("grodVersionCode")?.toInt() ?: 1
val releaseKeystore = setting("GROD_KEYSTORE_FILE")

android {
    namespace = "dev.grod"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.grod"
        minSdk = 26
        targetSdk = 35
        versionCode = grodVersionCode
        versionName = grodVersionName
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = setting("GROD_KEYSTORE_PASSWORD")
                keyAlias = setting("GROD_KEY_ALIAS")
                keyPassword = setting("GROD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Without the keystore settings the release stays unsigned, so local and fork builds still pass.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            val suffix = when {
                variant.buildType.name == "debug" -> "-debug"
                variant.signingConfig == null -> "-unsigned"
                else -> ""
            }
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "grod-${variant.versionName}$suffix.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.all {
            // Tests check the real rules file so a rule edit that Android would reject fails the build.
            it.systemProperty("grod.rulesFile", rootProject.file("../rules/grod.rules.json").absolutePath)
            it.systemProperty("grod.assetRulesFile", file("src/main/assets/rules.json").absolutePath)
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // android.jar only has stubs of org.json, so unit tests need the real implementation.
    testImplementation("org.json:json:20240303")
}
