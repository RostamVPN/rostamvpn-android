// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keyPropertiesFile = rootProject.file("key.properties")
val keyProperties = Properties().apply {
    if (keyPropertiesFile.exists()) {
        load(keyPropertiesFile.inputStream())
    }
}

android {
    compileSdk = 36
    namespace = "com.rostamvpn.android"
    defaultConfig {
        manifestPlaceholders["usesCleartextTraffic"] = "false"
        applicationId = "com.rostamvpn.android"
        minSdk = 23
        targetSdk = 36

        versionCode = 1
        versionName = "6.7.2"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    packaging {
        jniLibs {
            excludes += "**/libboringtun-*.so"
        }
    }

    androidResources {
        ignoreAssetsPatterns += "*.notes.md"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }
    bundle {
        language {
            enableSplit = false
        }
    }
    signingConfigs {
        create("release") {
            keyProperties.getProperty("storeFile")
                ?.takeIf { it.isNotBlank() }
                ?.let { storeFile = file(it) }
            storePassword = keyProperties.getProperty("storePassword", "")
            keyAlias = keyProperties.getProperty("keyAlias", "")
            keyPassword = keyProperties.getProperty("keyPassword", "")
        }

        keyProperties.stringPropertyNames()
            .filter { it.endsWith(".storeFile") }
            .map { it.removeSuffix(".storeFile") }
            .forEach { brand ->
                create(brand) {
                    storeFile = file(keyProperties.getProperty("$brand.storeFile"))

                    val pwFile = keyProperties.getProperty("$brand.passwordFile")
                        ?.let { file(it).readText().trim() }
                    storePassword = pwFile ?: keyProperties.getProperty("$brand.storePassword", "")
                    keyAlias = keyProperties.getProperty("$brand.keyAlias", "")
                    keyPassword = pwFile ?: keyProperties.getProperty("$brand.keyPassword", "")

                    keyProperties.getProperty("$brand.storeType")?.let { storeType = it }
                }
            }
    }
    buildTypes {
        getByName("debug") {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            isDebuggable = true
            isJniDebuggable = true
            isMinifyEnabled = false
            packaging {
                jniLibs.keepDebugSymbols.add("*/arm64-v8a/*.so")
                jniLibs.keepDebugSymbols.add("*/armeabi-v7a/*.so")
                jniLibs.keepDebugSymbols.add("*/x86/*.so")
                jniLibs.keepDebugSymbols.add("*/x86_64/*.so")
            }
        }
        getByName("release") {
            isMinifyEnabled = true
            ndk {
                debugSymbolLevel = "FULL"
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    flavorDimensions += listOf("brand", "store")
    productFlavors {
        val brandDir = rootProject.file("../../../brands")
        val brandFiles = brandDir.listFiles { f: java.io.File ->
            f.name.endsWith(".json") && f.name != "LOCKED.json"
        }?.sortedBy { it.name } ?: emptyList()
        require(brandFiles.isNotEmpty()) { "no brand manifests in $brandDir" }

        brandFiles.forEach { file ->
            @Suppress("UNCHECKED_CAST")
            val m = groovy.json.JsonSlurper().parse(file) as Map<String, Any?>
            val id = m["id"] as? String
                ?: error("${file.name}: missing \"id\"")
            val android = m["android"] as? Map<String, Any?>
                ?: error("${file.name}: missing \"android\"")
            val appId = android["applicationId"] as? String

            create(id) {
                dimension = "brand"

                signingConfig = signingConfigs.findByName(id)
                    ?: signingConfigs.getByName("release")

                if (appId != null) {
                    applicationId = appId
                } else {
                    tasks.matching { it.name.contains(id.replaceFirstChar { c -> c.uppercase() }) }
                        .configureEach { doFirst { error("brand '$id' has no applicationId in ${file.name}") } }
                }

                val cashier = m["cashier"] as? Map<String, Any?> ?: emptyMap()
                fun str(name: String, value: String) =
                    buildConfigField("String", name, "\"" + value + "\"")

                str("CASHIER_BRAND", cashier["brand"] as? String ?: id)
                str("CASHIER_BASE", cashier["base"] as? String
                    ?: error("${file.name}: missing cashier.base"))

                str("WGTCP_API_KEY", m["wgtcpApiKey"] as? String ?: "")

                @Suppress("UNCHECKED_CAST")
                val subs = (android["subProductIds"] as? List<String>).orEmpty()
                str("SUB_PRODUCT_IDS", subs.joinToString(","))

                val paidOnly = run {
                    val cfg = File(brandDir, "configs/$id.json")
                    if (!cfg.exists()) false else {
                        val c = groovy.json.JsonSlurper().parse(cfg) as Map<String, Any?>
                        when (val v = c["paid_only"]) {
                            null -> false
                            is Boolean -> v
                            else -> error("brands/configs/$id.json paid_only is $v; expected true or false")
                        }
                    }
                }
                buildConfigField("boolean", "PAID_ONLY", paidOnly.toString())

                val vc = (android["versionCode"] as? Number)?.toInt()
                if (vc != null) {
                    versionCode = vc
                } else {
                    tasks.matching { it.name.contains(id.replaceFirstChar { c -> c.uppercase() }) }
                        .configureEach { doFirst { error("brand '$id' has no versionCode in ${file.name}") } }
                }

                (android["versionName"] as? String)?.let { versionName = it }

                (android["minSdk"] as? Int)?.let { minSdk = it }
            }
        }

        create("play") {
            dimension = "store"
            buildConfigField("boolean", "WEB_PAY_ENABLED", "false")
        }
        create("direct") {
            dimension = "store"
            buildConfigField("boolean", "WEB_PAY_ENABLED", "true")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs.useLegacyPackaging = true
    }
    ndkVersion = "28.0.13004108"
    buildFeatures {
        buildConfig = true
        compose = true
    }
    lint {
        enable += "UnusedResources"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.core:core-splashscreen:1.2.0")

    implementation("com.google.android.material:material:1.14.0")

    implementation("com.android.billingclient:billing-ktx:8.0.0")

    implementation("com.google.android.gms:play-services-ads-identifier:18.3.0")

    implementation("com.google.zxing:core:3.5.4")

    implementation("com.google.android.play:review:2.0.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.0")
}
