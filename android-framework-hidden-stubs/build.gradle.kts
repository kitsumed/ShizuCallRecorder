plugins {
    id("com.android.library")
}
java {
    // Make those value match the app module one
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

android {
    namespace = "com.kitsumed.shizucallrecorder.hidden_api_stubs"
    // Make this match our app module one
    compileSdk = 36
}