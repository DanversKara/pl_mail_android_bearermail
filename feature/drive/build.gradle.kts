plugins {
    alias(libs.plugins.plmail.android.library)
    alias(libs.plugins.plmail.android.compose)
    alias(libs.plugins.plmail.android.hilt)
}

// BearerMail Drive: browse, upload from the phone, download, share links.
// Talks to the server's /jmap/drive API through :core:data's DriveRepository.
android { namespace = "de.plmail.feature.drive" }

dependencies {
    implementation(projects.core.data)
    implementation(projects.feature.notes)
    implementation(projects.core.designsystem)
    implementation(projects.core.jmap)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.core)
}

// No unit tests in this module yet, and Gradle 9 fails a test task that finds
// none. Remove this once the first test lands.
tasks.withType<Test>().configureEach { failOnNoDiscoveredTests = false }
