plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    jacoco
}

android {
    namespace = "com.aether.nextcloud"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aether.nextcloud"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    // Exported Room schemas, so the migration test can open the database as an older version created it.
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

    testOptions {
        // android.util.* and friends return defaults instead of throwing in plain JVM tests.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.browser)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // Lets XmlPullParserFactory resolve a parser in plain JVM tests (the platform one is compiled into Android).
    testImplementation(libs.kxml2)
    testImplementation(libs.xmlpull)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

jacoco {
    toolVersion = "0.8.13"
}

// One report from both test suites. Run the tests first: ./gradlew testDebugUnitTest connectedDebugAndroidTest coverageReport
tasks.register<JacocoReport>("coverageReport") {
    group = "verification"
    description = "Merges JVM and instrumented test coverage into build/reports/coverage/combined."

    val generated = listOf(
        "**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*",
        "**/*_Factory*", "**/Dagger*", "**/AetherApp_*", "**/di/AppModule_*", "**/*_GeneratedInjector*", "**/*_HiltModules*", "**/*_MembersInjector*", "**/*_Impl*", "**/Hilt_*", "**/dagger/**", "**/hilt_aggregated_deps/**",
        "**/*ComposableSingletons*", "**/*\$\$inlined*", "**/*\$Lambda*",
    )
    classDirectories.setFrom(fileTree(layout.buildDirectory.dir("intermediates/classes/debug/transformDebugClassesWithAsm/dirs")) { exclude(generated) })
    sourceDirectories.setFrom(layout.projectDirectory.dir("src/main/kotlin"))
    executionData.setFrom(
        fileTree(layout.buildDirectory) { include("outputs/unit_test_code_coverage/**/*.exec", "outputs/code_coverage/**/*.ec") },
    )
    reports {
        html.required = true
        xml.required = true
        html.outputLocation = layout.buildDirectory.dir("reports/coverage/combined")
        xml.outputLocation = layout.buildDirectory.file("reports/coverage/combined.xml")
    }
}
