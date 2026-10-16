// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

buildscript {
    repositories {
        maven { url = uri(rootDir.resolve("local-maven")) }
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.13.2")

        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")

        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.3.21")
    }
}

allprojects {
    repositories {
        maven { url = uri(rootDir.resolve("local-maven")) }
        google()
        mavenCentral()
    }
}

tasks.register("clean").configure {
    delete("build")
}

