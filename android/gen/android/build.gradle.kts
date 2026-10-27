buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.13.2")
        // Kotlin 2.3.21, deliberately NOT the newest 2.4.x. AGP 8.13.2 ships an R8
        // that does not understand Kotlin 2.4 metadata: on 2.4.10 every build logged
        // "error occurred when parsing kotlin metadata" and one release build died
        // with an R8 NullPointerException inside the PubMatic SDK -- then succeeded
        // unchanged on retry. A release process that fails intermittently is worse
        // than one that fails honestly. On 2.3.21 that warning count is zero.
        // Raise this only together with AGP, and check the R8 warnings are still 0.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
        // Kotlin 2.x moved the Compose compiler out of the Kotlin plugin and into
        // its own plugin; composeOptions.kotlinCompilerExtensionVersion no longer
        // applies and its version must track the Kotlin version exactly.
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.3.21")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        // ── AdMob mediation network SDK repos ────────────────────────────────
        // Several networks host their SDKs on their OWN Maven, not Google/Central.
        // Without these the adapter POMs resolve but their transitive network
        // SDKs (mbridge, pangle, applovin) 404. Scoped by group so they are only
        // consulted for their own artifacts.
        maven {
            url = uri("https://dl-maven-android.mintegral.com/repository/mbridge_android_sdk_oversea")
            content { includeGroupByRegex("com\\.mbridge.*") }
        }
        maven {
            url = uri("https://artifact.bytedance.com/repository/pangle")
            content { includeGroupByRegex("com\\.pangle.*") }
        }
        maven {
            url = uri("https://artifacts.applovin.com/android")
            content { includeGroupByRegex("com\\.applovin.*") }
        }
        maven {
            url = uri("https://cboost.jfrog.io/artifactory/chartboost-ads/")
            // Chartboost also hosts its IAB Open Measurement build here, under
            // the com.iab.omid.library group — not on Google/Central.
            content {
                includeGroupByRegex("com\\.chartboost.*")
                includeGroupByRegex("com\\.iab\\.omid.*")
            }
        }
        maven {
            url = uri("https://repo.pubmatic.com/artifactory/public-repos")
            content { includeGroupByRegex("com\\.pubmatic.*") }
        }
    }
}

tasks.register("clean").configure {
    delete("build")
}

