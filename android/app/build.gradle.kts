plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.grod"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.grod"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
