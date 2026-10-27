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

// assets/default-config.json is the COLD-START FALLBACK config: loaded on first
// boot (VpnViewModel init → ConfigManager.loadFromJson) before the OTA config
// fetch overrides it. There is NO local runtime-config.json — the canonical
// config is served OTA by the config server. Refresh this fallback from a
// known-good OTA snapshot when endpoints rotate, so first-boot-before-OTA still
// has a working server set.

// Brands that link an ad SDK. Filled by the productFlavors loop from each brand
// manifest's `android.adtech` (absent = true), and read by `dependencies` so the
// ad SDKs are added PER BRAND instead of to every variant. Script scope because
// those two blocks cannot see each other's locals.
val adtechBrands = mutableSetOf<String>()

// Brands with verified web App Links (`deepLinkHosts` in the brand manifest):
// brand id -> hosts. Filled by the productFlavors loop; read after `android {}`
// to attach a GENERATED manifest to `<brand>Play` and `<brand>Direct`. The
// multi-flavour source sets are used, not `<brand>`, because `<brand>`'s
// manifest slot is already taken by src/withAds for an adtech brand.
val deepLinkBrands = mutableMapOf<String, List<String>>()

// Brands with the native eSIM store's card sheet (Stripe PaymentSheet): a
// `navigation: tabs` brand that sells eSIM. Filled by the productFlavors loop,
// read by `dependencies` so the Stripe SDK is linked PER BRAND, exactly like
// the ad SDK: every other brand compiles src/noCardSheet instead and carries
// none of Stripe's code, permissions or size.
val cardSheetBrands = mutableSetOf<String>()

// Every brand id, and the advertising-id switch per brand (owner 2026-10-25,
// Filled by the productFlavors
// loop from `android.advertisingId` (absent = true, so every other brand is
// unchanged), read after `android {}`: noAdIdBrands link no
// play-services-ads-identifier and no Firebase Analytics (which pulls it),
// exclude it from Adapty's graph, compile the src/noAdId twins and REMOVE the
// AD_ID permission in a generated manifest.
val allBrands = mutableListOf<String>()
val noAdIdBrands = mutableSetOf<String>()
val noAdsBrands = mutableSetOf<String>()
// Brands that actually sell through Adapty. The SDK is linked ONLY for these
// (see the dependencies block) and the service/paywall host come from
// src/adapty vs src/noAdapty. A brand with no key used to link ~2 MB of
// third-party billing and analytics code that could never run.
val adaptyBrands = mutableSetOf<String>()
// Per-brand tunnel-health probe origin. The default is the shared worker; a
// brand that puts `features.healthProbeUrl` in its manifest gets its own, and
// no other brand's host is then compiled into it. Read by the boot-config
// generator below AND emitted as BuildConfig.HEALTH_PROBE_URL, because the
// bundled asset and ConfigManager's hardcoded fallback are two separate copies
// of the same value and a brand leak through either one is still a leak.
val healthProbeUrls = mutableMapOf<String, String>()
// Per-brand override for the BUNDLED boot config's `throttle.hard_stop`.
// main's asset is shared, so a brand that wants a different value gets it
// patched in rather than forking the file. Absent = leave the asset alone.
val throttleHardStop = mutableMapOf<String, Boolean>()
val defaultHealthProbeUrl = "https://ip-geo.example.com/"

android {
    compileSdk = 36
    namespace = "com.rostamvpn.android"
    defaultConfig {
        manifestPlaceholders["usesCleartextTraffic"] = "false"
        // AdMob App ID injected into the manifest. Default = Google's official
        // SAMPLE app id, so any brand/build that doesn't override it still
        // initializes the SDK against test inventory (never real, never a policy
        // risk). Real ids are set per brand + per build type below. The SDK
        // HARD-CRASHES at init if this meta-data is absent, so it must always
        // resolve to a value.
        manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"
        applicationId = "com.rostamvpn.android"
        minSdk = 23
        targetSdk = 36
        // A FLOOR, never what ships. Every brand overrides this from its own
        // manifest below, because versionCode is a PER-LISTING counter: Play
        // enforces it separately for each applicationId, so one shared number
        // cannot be correct for five stores at once. It was: this single value
        // sat 72 ahead of rostam's store lineage and 183 ahead of redvpn's, and
        // shipping it would have burned every code in between permanently --
        // codes are never reusable, so the gap would have locked those listings
        // out of their own numbering forever.
        versionCode = 1
        versionName = "6.7.2"

        // AdMob interstitial ad unit id. Default = Google's official TEST
        // interstitial unit, so every build serves test ads until a brand +
        // release build overrides it with a real unit. buildTypes.release on the
        // rostam brand supplies the real one; debug ALWAYS keeps the test unit so
        // on-device development never fires real impressions (invalid-traffic
        // protection for the shared pub-3666521938553232 account).
        buildConfigField("String", "ADMOB_INTERSTITIAL_ID",
            "\"ca-app-pub-3940256099942544/1033173712\"")

        // Comma-separated AdMob test-device hash ids. When a device's hash is
        // listed here, the REAL ad units return real ads flagged "Test Ad" — real
        // fill + real mediation, but clicks don't count as invalid traffic. This
        // is how the release build is tested against live inventory safely.
        // Get a device's hash from logcat on the first ad request:
        //   adb logcat | grep "Use RequestConfiguration.Builder().setTestDeviceIds"
        // then paste the hash(es) here (e.g. "\"ABC123...,DEF456...\"").
        // Empty = no test devices (production behaviour). Applies to ALL builds
        // so a release build installed via internal testing can be verified.
        // R5CY13EDP7A (SM-A166U1) — the project test handset. Registered so the
        // REAL ad units serve real ads flagged "Test Ad" on it: real fill and real
        // mediation, but taps are never counted as invalid traffic against the
        // shared pub-3666521938553232 account (AdMob + AdSense).
        buildConfigField("String", "ADMOB_TEST_DEVICE_IDS",
            "\"FB4CC759FB00A2AC5E0D94F66D2BCC08\"")

        // Carrier-tuned tester builds: -PpreferHosts=host=prio,host=prio gives
        // those entries an explicit priority (lower dials first). Empty in
        // normal builds, which then behave exactly as before.
        buildConfigField("String", "PREFER_HOSTS",
            "\"" + (project.findProperty("preferHosts") as String? ?: "") + "\"")

        // The eSIM store ORIGIN (paths carry /v1). EMPTY here on purpose: the
        // per-brand loop sets the real host only for a brand whose manifest says
        // `features.esim`. It used to be unconditional, so RostamVPN -- which
        // sells no eSIM and has the flag false -- still carried the store's host
        // in its dex, switched off at runtime but perfectly readable. A runtime
        // flag does not remove a string from a binary.
        buildConfigField("String", "ESIM_BASE", "\"\"")
        // Tunnel-health probe. Per-brand (see healthProbeUrls); this is only the
        // value a brand inherits when its manifest does not name one.
        buildConfigField("String", "HEALTH_PROBE_URL", "\"" + defaultHealthProbeUrl + "\"")

        // Only include ARM architectures (99% of real devices)
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    packaging {
        jniLibs {
            // cargo-ndk copies every .so it finds in the cargo output dir, which
            // includes boringtun built as a dependency dylib. Nothing links it --
            // boringtun is statically linked into libwgtcp_core.so, and no
            // DT_NEEDED or loadLibrary references it -- so it is 323 KB of dead
            // weight in every arm64 APK. Deleting it by hand does not stick; it
            // reappears on the next cargo ndk run. Exclude it at packaging time.
            excludes += "**/libboringtun-*.so"
        }
    }

    androidResources {
        // tools/config/strip-notes.py moves the config's prose out of
        // default-config.json into "<config>.notes.md next to the file" -- and
        // next to the file IS src/main/assets, so the notes were packaged right
        // back into every APK. 9.8 KB of internal design notes shipped to the
        // device: the fallback API hosts and why they share one IP, the OAuth
        // client id, the freedom-region and IPv4-first-in-Iran reasoning, the
        // watchdog design. That is a decompiler's roadmap in an app whose whole
        // point is not to be legible to the censor (CLAUDE.md 0, and the stealth
        // rename in 7), and it undoes the byte saving the strip was for.
        // The notes stay where their authors expect them; they just stop shipping.
        ignoreAssetsPatterns += "*.notes.md"
    }

    // Build separate APKs per architecture for smaller downloads
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }
    bundle {
        // Ship EVERY language in the base AAB instead of splitting by device
        // locale. Play's default is to deliver only the split matching the
        // device's language, which silently defeats the in-app / per-app
        // language picker: a user in Iran whose phone is set to Persian who
        // picks English (or Kurdish, or Azerbaijani) gets untranslated
        // fallback, because that split was never installed. Costs a few
        // hundred KB; buys a language picker that actually works.
        language {
            enableSplit = false
        }
    }
    signingConfigs {
        create("release") {
            storeFile = file(keyProperties.getProperty("storeFile", ""))
            storePassword = keyProperties.getProperty("storePassword", "")
            keyAlias = keyProperties.getProperty("keyAlias", "")
            keyPassword = keyProperties.getProperty("keyPassword", "")
        }
        // ── Per-brand keys ────────────────────────────────────────────────────
        // Play enforces the upload certificate PER LISTING, so one `release`
        // config cannot serve every brand: com.redvpn.android expects
        // 62:29:9D:8D:… and rejects the shared new_alias outright with a 403.
        //
        // Declared by DATA, not by name, so adding a brand key never edits this
        // file: any `<brand>.storeFile` in key.properties creates a config called
        // `<brand>`, which the flavor loop below picks up. key.properties is
        // gitignored, so the passwords are not in the repo.
        keyProperties.stringPropertyNames()
            .filter { it.endsWith(".storeFile") }
            .map { it.removeSuffix(".storeFile") }
            .forEach { brand ->
                create(brand) {
                    storeFile = file(keyProperties.getProperty("$brand.storeFile"))
                    // `<brand>.passwordFile` keeps the password out of
                    // key.properties entirely: a mode-600 file outside every
                    // repo, holding one password used for store and key.
                    val pwFile = keyProperties.getProperty("$brand.passwordFile")
                        ?.let { file(it).readText().trim() }
                    storePassword = pwFile ?: keyProperties.getProperty("$brand.storePassword", "")
                    keyAlias = keyProperties.getProperty("$brand.keyAlias", "")
                    keyPassword = pwFile ?: keyProperties.getProperty("$brand.keyPassword", "")
                    // RedVPN's key is a PKCS#12 exported from Play Console, not a
                    // JKS. Without this the store opens as JKS and fails.
                    keyProperties.getProperty("$brand.storeType")?.let { storeType = it }
                }
            }
    }
    buildTypes {
        getByName("debug") {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            // Debug ALWAYS uses AdMob TEST ids, overriding any brand's real id
            // (buildType overrides flavor in AGP). On-device development must
            // never fire a real impression — real clicks from a dev device are
            // invalid traffic and can strike the whole pub-3666521938553232
            // account (AdMob + AdSense share it).
            manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"
            buildConfigField("String", "ADMOB_INTERSTITIAL_ID",
                "\"ca-app-pub-3940256099942544/1033173712\"")
            // Debug only: -PesimBase=http://10.0.2.2:8031 points the native
            // eSIM store at a local e-sim.net in fake mode (the emulator's
            // 10.0.2.2 is the host's loopback). Debug already allows
            // cleartext. Absent = the real host.
            // No `?: "https://e-sim.net"` fallback: a buildType overrides the
            // flavour, so a default here would put the host back into every
            // debug build of a brand that does not sell eSIM. Absent property =
            // keep whatever the brand set.
            (project.findProperty("esimBase") as String?)?.let {
                buildConfigField("String", "ESIM_BASE",
                    "\"" + it.trimEnd('/').removeSuffix("/v1") + "\"")
            }
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
            // signingConfig is set PER BRAND in productFlavors below. Setting it
            // here would outrank the flavor and put every brand on one key.
            isMinifyEnabled = true
            ndk {
                debugSymbolLevel = "FULL"
            }
            proguardFiles(
                *fileTree(".") { include("**/*.pro") }
                    .plus(getDefaultProguardFile("proguard-android-optimize.txt"))
                    .toList().toTypedArray()
            )
        }
    }
    // Distribution flavors: `play` = Play Store (Play Billing only), `direct` =
    // direct-APK/web (Paymentwall + crypto allowed; Play policy doesn't apply).
    // WEB_PAY_ENABLED gates the card/crypto pay UI so it can NEVER ship in Play.
    // Two dimensions: brand x store. Brand is first, so variants read
    // `<brand><Store><BuildType>` — e.g. `rostamPlayRelease`.
    //
    // Adding a brand is Gradle + resources only, no Kotlin changes: give it a
    // flavor below, then optionally `src/<brand>/res/` (icon, colors, strings) and
    // `src/<brand>/assets/default-config.json`. Everything brand-specific reaches
    // the code through BuildConfig, so there is never an `if (brand == …)`.
    flavorDimensions += listOf("brand", "store")
    productFlavors {
        // ── Brands: generated from /brands/*.json ─────────────────────────────
        // The manifests are the ONLY place a brand is described, and iOS reads the
        // same files (tools/whitelabel/gen-ios.py). Adding a brand is a JSON file
        // plus resource overlays -- never an edit here, and never an edit in
        // Kotlin: everything below reaches code through BuildConfig, so there is
        // no `if (brand == …)` anywhere in the app.
        //
        // Values are NOT defaulted. A missing applicationId fails the build rather
        // than inventing one, because these ids are permanent and externally
        // owned: a wrong one publishes a NEW app that cannot update the existing
        // install base. Run tools/whitelabel/validate.py before releasing -- it
        // also refuses a brand whose versionCode lineage does not match the store.
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
                // A brand with its own upload key uses it; everything else stays
                // on the shared `release` config. A flavor signingConfig outranks
                // the buildType's, which is what makes this work at all.
                signingConfig = signingConfigs.findByName(id)
                    ?: signingConfigs.getByName("release")
                // A brand with no applicationId yet is defined but not buildable.
                // Skipping the id leaves the base one in place, which would
                // publish under another brand's identity -- so fail loudly at
                // assemble time instead, and only for that brand.
                if (appId != null) {
                    applicationId = appId
                } else {
                    tasks.matching { it.name.contains(id.replaceFirstChar { c -> c.uppercase() }) }
                        .configureEach { doFirst { error("brand '$id' has no applicationId in ${file.name}") } }
                }

                // ── Adtech, per brand ────────────────────────────────────────
                // A brand that sells no ads links NO ad SDK, rather than merely
                // switching ads off in config. AdMob contributes
                // `com.google.android.gms.permission.AD_ID`, and Play's
                // advertising-id declaration asks about the PERMISSION, not
                // about whether an ad ever renders — so an ads-off brand still
                // had to declare that it uses an advertising id, which is §22's
                // "listing contradicts the binary" shape.
                //
                // Absent = true, so every brand that existed before this keeps
                // exactly what it had.
                val adtech = (android["adtech"] as? Boolean) ?: true
                // BuildConfig.ADS false = the server-switchable interstitial
                // waterfall (AdMob rung, the cashier/house WebView rung, the
                // `ads` config block) is unreachable, so R8 drops it: no OTA
                // edit can put an ad in front of a user of this brand. The
                // bundled boot config also loses its `ads` block (below).
                buildConfigField("boolean", "ADS", adtech.toString())
                if (!adtech) noAdsBrands += id
                // Per-brand minSdk: Play "automatic protection" (on for dollarvpn) refuses
                // bundles below 24. Absent = the module default.
                (android["minSdk"] as? Int)?.let { minSdk = it }
                if (adtech) adtechBrands += id
                allBrands += id
                val advertisingId = (android["advertisingId"] as? Boolean) ?: true
                require(advertisingId || !adtech) {
                    "${file.name}: an ad SDK (adtech) needs the advertising id; set adtech false first"
                }
                if (!advertisingId) noAdIdBrands += id
                buildConfigField("boolean", "ADVERTISING_ID", advertisingId.toString())
                // Per-app attribution (RedShield Traffic by app): the tun bridge
                // built from tools/hev (upstream + origin-tag.patch) replaces
                // main's prebuilt for this brand, and HevConfig asks it to tag
                // each TCP session with its app-side socket. Absent = false.
                val appAttribution = (android["appAttribution"] as? Boolean) ?: false
                buildConfigField("boolean", "APP_ATTRIBUTION", appAttribution.toString())
                if (appAttribution) sourceSets.getByName(id) { jniLibs.srcDir("src/hevOrigin/jniLibs") }
                sourceSets.getByName(id) {
                    java.srcDir(if (advertisingId) "src/adId/java" else "src/noAdId/java")
                }
                sourceSets.getByName(id) {
                    java.srcDir(if (adtech) "src/withAds/java" else "src/noAds/java")
                    if (adtech) manifest.srcFile("src/withAds/AndroidManifest.xml")
                }
                // Tests of the src/withAds code compile only where that code does
                // (a no-ads brand's unit tests failed to compile on them).
                if (adtech) sourceSets.maybeCreate("test" + id.replaceFirstChar { it.uppercase() }).java.srcDir("src/testWithAds/java")

                val cashier = m["cashier"] as? Map<String, Any?> ?: emptyMap()
                fun str(name: String, value: String) =
                    buildConfigField("String", name, "\"" + value + "\"")

                str("CASHIER_BRAND", cashier["brand"] as? String ?: id)
                str("CASHIER_BASE", cashier["base"] as? String
                    ?: error("${file.name}: missing cashier.base"))
                // Ships in the APK and is therefore public by construction, like
                // the cashier X-App-Key. Write-only provisioning: it lets a caller
                // ask for a profile, not read anyone else's. The real guards are
                // the per-cid key cap and the rate limit.
                str("WGTCP_API_KEY", m["wgtcpApiKey"] as? String ?: "")
                // Adapty's PUBLIC SDK key. Empty for a brand that has none, and
                // AdaptyService then never activates -- which is how rostam
                // links the SDK without using it. Read from the manifest TOP
                // LEVEL, the same value gen-ios.py reads: one Adapty app covers
                // both platforms, so a per-platform copy could drift and one
                // side would activate against the wrong app.
                val adaptyKey = (m["adaptyPublicKey"] as? String).orEmpty().trim()
                str("ADAPTY_PUBLIC_KEY", adaptyKey)
                // The key decides whether the SDK is linked at all, not just
                // whether it activates. Keyless brands get the stub service and
                // paywall host from src/noAdapty, which reproduce exactly what
                // the real ones did with no key: `ready` empty, signals
                // dropped, and the built-in PaywallScreen rendered.
                if (adaptyKey.isNotEmpty()) adaptyBrands += id
                sourceSets.getByName(id) {
                    java.srcDir(if (adaptyKey.isNotEmpty()) "src/adapty/java" else "src/noAdapty/java")
                }
                // Firebase Analytics, for Google Ads conversion optimisation.
                //
                // 🚨 NO google-services PLUGIN, deliberately. The plugin demands a
                // google-services.json for EVERY flavour it sees and fails the
                // build without one -- rostam, internetpro and goglobe have no
                // Firebase project, so adopting it would break three brands to
                // instrument two. These four values are all FirebaseOptions
                // needs, and an empty appId means the SDK never initialises.
                @Suppress("UNCHECKED_CAST")
                val fb = (m["firebase"] as? Map<String, Any?>).orEmpty()
                str("FIREBASE_APP_ID", fb["appId"] as? String ?: "")
                str("FIREBASE_API_KEY", fb["apiKey"] as? String ?: "")
                str("FIREBASE_PROJECT_ID", fb["projectId"] as? String ?: "")
                str("FIREBASE_SENDER_ID", fb["senderId"] as? String ?: "")
                // 🚨 ADAPTY IS THE ONLY SELLING SURFACE for a brand that sets
                // this. The built-in PaywallScreen then renders ONLY the
                // entitled state, never the offer -- Adapty has no entitled
                // state, so that half cannot move. Owner's call, 2026-09-26.
                //
                // The cost is real and is mitigated, not ignored: these users
                // are behind censorship BEFORE the tunnel exists and the Adapty
                // SDK does not speak our SOCKS proxy, so a blocked
                // api.adapty.io used to mean "worse paywall" and now means "no
                // offer". AdaptyService therefore fetches CACHE-FIRST, so a
                // flow loaded once keeps rendering offline.
                buildConfigField("boolean", "ADAPTY_ONLY",
                    (m["adaptyOnly"] as? Boolean ?: false).toString())
                // Subscription ids are PER-APP and read from Play Console, never
                // shared. Empty is honest: the paywall says "unavailable" rather
                // than offering SKUs that do not exist. A wrong id fails SILENTLY
                // -- queryProducts returns an empty list.
                @Suppress("UNCHECKED_CAST")
                val subs = (android["subProductIds"] as? List<String>).orEmpty()
                str("SUB_PRODUCT_IDS", subs.joinToString(","))

                // Real AdMob ids, release builds only -- the debug buildType forces
                // Google's sample ids regardless, so dev never fires a real
                // impression. A brand without them simply serves no AdMob.
                // Per-brand feature switches. A brand that does not sell eSIM has
                // no eSIM UI at all -- the flag reaches Kotlin through BuildConfig
                // so there is still no `if (brand == …)` anywhere.
                @Suppress("UNCHECKED_CAST")
                val features = (m["features"] as? Map<String, Any?>).orEmpty()
                val sellsEsim = features["esim"] as? Boolean ?: false
                buildConfigField("boolean", "FEATURE_ESIM", sellsEsim.toString())
                // The host itself, not just the flag. A brand that does not sell
                // eSIM gets "" and the store's origin never reaches its dex.
                buildConfigField("String", "ESIM_BASE",
                    if (sellsEsim) "\"https://e-sim.net\"" else "\"\"")
                // ...and the four strings in main/res that name the store's
                // domain. The dex was cleared in 476; these four lived on in
                // resources.arsc, in 39 languages. An OVERRIDE, not a move:
                // they are referenced from src/main/java, which every brand
                // compiles, so deleting them from main is an Unresolved
                // reference everywhere. The resource merge lets the flavour
                // value win instead. All 39 qualifiers are present in
                // src/noEsim/res because a locale qualifier beats a source-set
                // override: a copy in values/ alone still loses to
                // main/res/values-fa and the domain ships in Persian anyway.
                if (!sellsEsim) sourceSets.getByName(id) { res.srcDir("src/noEsim/res") }
                // Tunnel-health probe origin, per brand for the same reason.
                val probeUrl = (features["healthProbeUrl"] as? String)?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: defaultHealthProbeUrl
                healthProbeUrls[id] = probeUrl
                (features["throttleHardStop"] as? Boolean)?.let { throttleHardStop[id] = it }
                buildConfigField("String", "HEALTH_PROBE_URL", "\"" + probeUrl + "\"")
                // On-device malware protection from a signed blocklist. Absent
                // = false, so only a brand that opts in can ever load a list;
                // the OTA `protection.enabled` is the second, remote switch.
                buildConfigField("boolean", "FEATURE_PROTECTION",
                    (features["protection"] as? Boolean ?: false).toString())
                // The device-side protection's display name (REDSHIELD-TRAFFIC,
                // Amendment B), the twin of iOS BRAND_PROTECTION_NAME: RedVPN
                // calls it "RedShield". Empty = the brand has no such feature;
                // every RedShield gate in Kotlin is PROTECTION_NAME.isNotEmpty(),
                // so the name and the feature come from the manifest together.
                // Separate from FEATURE_PROTECTION, which is the exit-side path.
                val protectionName = (features["protectionName"] as? String).orEmpty().trim()
                require(protectionName.none { it == '"' || it == '\\' || it == '$' }) {
                    "${file.name}: features.protectionName has a character BuildConfig cannot carry"
                }
                str("PROTECTION_NAME", protectionName)
                // Where Protect Mode's API lives for this brand (score, scan,
                // profiles, geo). Absent = vpn.red, where it was first published;
                // a brand on its own domain keeps the other brand's host out of
                // its binary and out of its own Traffic view.
                val protectionApiBase = (features["protectionApiBase"] as? String)?.trim()?.trimEnd('/').takeUnless { it.isNullOrEmpty() } ?: "https://vpn.red"
                require(Regex("^https://[a-z0-9.-]+$").matches(protectionApiBase)) {
                    "${file.name}: features.protectionApiBase '$protectionApiBase' must be a bare https origin"
                }
                str("PROTECTION_API_BASE", protectionApiBase)
                // The privacy policy the in-app disclosures link to (VPN and
                // Protect Mode, 2026-10-26). From the manifest's legal block;
                // empty = no link rendered.
                @Suppress("UNCHECKED_CAST")
                val privacyUrl = ((m["legal"] as? Map<String, Any?>)?.get("privacyUrl") as? String).orEmpty().trim()
                require(privacyUrl.isEmpty() || Regex("^https://[a-z0-9.-]+(/[A-Za-z0-9._~/-]*)?$").matches(privacyUrl)) {
                    "${file.name}: legal.privacyUrl '$privacyUrl' must be a plain https URL"
                }
                str("PRIVACY_URL", privacyUrl)
                // Freedom regions (owner 2026-10-25): false = this brand has no
                // free tier anywhere, every country gets the same trial then the
                // paywall. ConfigManager.freedomTier folds it in, so the single
                // choke point (VpnViewModel.freedomRegion) and everything behind
                // it (unlimited tier, hidden purchase UI, paywall skip, meter)
                // inherit it. Absent = true, today's behaviour for every other brand.
                buildConfigField("boolean", "FREE_REGIONS",
                    (features["freeRegions"] as? Boolean ?: true).toString())
                // eSIM data plans and numbers are telecom services consumed
                // outside the app, which Play's Payments policy exempts from Play
                // Billing (decision 2026-10-13, flagged to the owner). A brand
                // opts in here to keep the e-sim.net checkout sheet in its Play
                // build; every other brand keeps the play-flavour gate. It never
                // touches the VPN subscription, which stays on Play Billing.
                // Live status surfaces (owner 2026-10-17): the stats foreground
                // notification, the Quick Settings tile and the widget. Absent =
                // false, so every other brand keeps today's plain notification;
                // the tile and widget exist only in the opted-in flavour manifest.
                buildConfigField("boolean", "FEATURE_LIVE_STATUS",
                    (features["liveStatus"] as? Boolean ?: false).toString())
                buildConfigField("boolean", "ESIM_CHECKOUT_ON_PLAY",
                    (features["esimCheckoutOnPlay"] as? Boolean ?: false).toString())
                // Top-level navigation, mirrored from iOS (`ios.navigation`):
                // "tabs" = Home · eSIM · Account in a bottom bar (RingsTabsRoot);
                // absent or "drawer" = the drawer every other brand keeps.
                // Orthogonal to `branding.style`: redvpn stays `rings` and only
                // its navigation changes, as on iOS. An unknown value fails the
                // build rather than shipping the drawer to a brand that asked
                // for something else.
                val navigation = (android["navigation"] as? String) ?: "drawer"
                require(navigation == "drawer" || navigation == "tabs") {
                    "${file.name}: android.navigation is '$navigation'; expected \"tabs\" or \"drawer\""
                }
                val navTabs = navigation == "tabs"
                buildConfigField("boolean", "NAV_TABS", navTabs.toString())
                val cardSheet = navTabs && (features["esim"] as? Boolean ?: false)
                if (cardSheet) cardSheetBrands += id
                sourceSets.getByName(id) {
                    java.srcDir(if (cardSheet) "src/cardSheet/java" else "src/noCardSheet/java")
                }
                // Web App Links (2026-10-24): https://<host>/esim, /me, /app/open …
                // open THIS app. Hosts come only from the manifest; absent = no
                // filter and both fields empty, so WebLinks.kt matches nothing
                // and the account surface stays off. The FIRST host is the one
                // the account screen names and signs into.
                @Suppress("UNCHECKED_CAST")
                val linkHosts = (m["deepLinkHosts"] as? List<String>).orEmpty()
                linkHosts.forEach { h ->
                    require(Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$").matches(h)) {
                        "${file.name}: deepLinkHosts entry '$h' is not a lowercase host name"
                    }
                }
                str("DEEP_LINK_HOSTS", linkHosts.joinToString(","))
                str("WEB_ACCOUNT_HOST", linkHosts.firstOrNull() ?: "")
                if (linkHosts.isNotEmpty()) deepLinkBrands[id] = linkHosts
                // PAID ONLY is a BUILD fact, read from brands/configs/<id>.json
                // `paid_only` -- the same document the bundled config is copied
                // from and that gen-ios.py reads for BRAND_PAID_ONLY, so the two
                // platforms share one source. NOT from the downloaded config: a
                // stale or missing per-brand OTA slot (or one /v2 404 that pins
                // the v1 path) would otherwise turn the paywall gate off.
                // ConfigManager ORs the remote value in; it can only tighten.
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

                // Continues THIS listing's lineage. Missing fails the brand's
                // own tasks rather than silently falling back to the floor,
                // which would upload a 1 against a live listing and be
                // rejected -- or worse, be accepted for a listing that has
                // none and start a second lineage.
                val vc = (android["versionCode"] as? Number)?.toInt()
                if (vc != null) {
                    versionCode = vc
                } else {
                    tasks.matching { it.name.contains(id.replaceFirstChar { c -> c.uppercase() }) }
                        .configureEach { doFirst { error("brand '$id' has no versionCode in ${file.name}") } }
                }

                // versionName is PER LISTING too, for exactly the reason
                // versionCode is (§6b). It lived only in `defaultConfig`, so
                // every brand shipped RostamVPN's string: RedVPN's store page
                // was about to jump 2.1.5 -> 6.7.2, into another product's
                // numbering, on a 63.5K-install listing. Unlike versionCode a
                // missing one is harmless, so it falls back rather than failing
                // the build -- a brand that has never had its own lineage is
                // better off on the shared string than stopped.
                (android["versionName"] as? String)?.let { versionName = it }

                // PER-BRAND minSdk, because Play's "automatic protection" is a
                // PER-LISTING setting and it hard-refuses a bundle below 24:
                //
                //   403 PERMISSION_DENIED -- "Play automatic protection requires
                //   a minimum SDK version of 24 or higher. The uploaded App
                //   Bundle has a minimum SDK version of 23."
                //
                // rostam and redvpn uploaded the same minSdk minutes earlier, so
                // this is not a property of the artifact -- it is enabled on
                // dollarvpn's listing and not on theirs. The global default
                // stays 23 for exactly that reason: raising it would strand
                // Android 6.0 users on rostam, which is 691K installs on the
                // oldest hardware of the three.
                //
                // ⚠️ It does strand Android 6.0 (API 23) users of THIS brand on
                // whatever they have. The alternative is turning automatic
                // protection off in dollarvpn's Console, which is an owner
                // action and a deliberate loss of anti-tamper.
                (android["minSdk"] as? Int)?.let { minSdk = it }

                val admobApp = android["admobAppId"] as? String
                val admobInt = android["admobInterstitialId"] as? String
                if (admobApp != null) manifestPlaceholders["admobAppId"] = admobApp
                str("ADMOB_INTERSTITIAL_ID", admobInt ?: "")
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
}

// ── Web App Links manifest, generated per brand from `deepLinkHosts` ──────────
// An intent filter cannot be switched on by a manifest placeholder (a filter
// with an empty host would match every host), so the brands that opt in get a
// generated manifest merged over src/main. It is written by a task hooked to
// preBuild, not at configuration time, so `clean` cannot delete it out from
// under a build that was configured before the clean ran.
//
// The paths are the ones WebLinks.kt routes; keep the two lists together.
// `android:autoVerify` makes Android 12+ fetch
// https://<host>/.well-known/assetlinks.json for EVERY host in the filter; a
// host that does not list this package and signing cert leaves the link to the
// browser (disambiguation dialog on Android 11 and below), never broken.
// The transport core, one library for every brand and both stores. It lives in
// its own source set rather than src/main/jniLibs because that directory is
// .gitignored for locally built artifacts, and this .so is the one native
// library the repository actually commits.
android.sourceSets.getByName("main").jniLibs.srcDir("src/core/jniLibs")

// The bundled boot config, patched per brand and written to the build dir, then
// overlaid on the brand's assets. Only brands that do NOT carry their own
// src/<brand>/assets/default-config.json: a generated copy of main's config in
// the same source set would collide with it or replace it. A brand's `ads`
// block stays in its own bundled file but is never read when BuildConfig.ADS is
// false (ConfigManager uses AdsConfig.OFF).
allBrands.filter { !file("src/$it/assets/default-config.json").exists() }.forEach { brand ->
    val src = file("src/main/assets/default-config.json")
    val outDir = layout.buildDirectory.dir("generated/brandAssets/$brand")
    val cap = brand.replaceFirstChar { it.uppercase() }
    val gen = tasks.register("generate${cap}BootConfig") {
        inputs.file(src)
        inputs.property("noAds", brand in noAdsBrands)
        inputs.property("probeUrl", healthProbeUrls[brand] ?: defaultHealthProbeUrl)
        inputs.property("hardStop", (throttleHardStop[brand] ?: "unset").toString())
        outputs.dir(outDir)
        doLast {
            @Suppress("UNCHECKED_CAST")
            val cfg = groovy.json.JsonSlurper().parse(src) as MutableMap<String, Any?>
            // The bundled boot config is the SECOND copy of the health-probe
            // host (the first is BuildConfig.HEALTH_PROBE_URL). main's asset is
            // shared by several brands, so the per-brand value is substituted
            // here rather than forking the whole file per brand.
            @Suppress("UNCHECKED_CAST")
            (cfg["health"] as? MutableMap<String, Any?>)?.put("probe_url", healthProbeUrls[brand] ?: defaultHealthProbeUrl)
            // The bundled config is only a fallback, but it is what a fresh
            // install runs on until its first OTA fetch succeeds -- long enough
            // to matter behind a block. Keep it saying what the server says.
            @Suppress("UNCHECKED_CAST")
            throttleHardStop[brand]?.let { (cfg["throttle"] as? MutableMap<String, Any?>)?.put("hard_stop", it) }
            // No ad vocabulary in a no-ads brand's APK at all (BuildConfig.ADS).
            if (brand in noAdsBrands) cfg.remove("ads")
            val f = outDir.get().file("default-config.json").asFile
            f.parentFile.mkdirs()
            f.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(cfg)))
        }
    }
    android.sourceSets.getByName(brand).assets.srcDir(outDir)
    tasks.named("preBuild") { dependsOn(gen) }
}

(deepLinkBrands.keys + noAdIdBrands).forEach { brand ->
    val hosts = deepLinkBrands[brand].orEmpty()
    val noAdId = brand in noAdIdBrands
    val out = layout.buildDirectory.file("generated/brandManifests/$brand/AndroidManifest.xml")
    val cap = brand.replaceFirstChar { it.uppercase() }
    val gen = tasks.register("generate${cap}DeepLinkManifest") {
        inputs.property("hosts", hosts)
        inputs.property("noAdId", noAdId)
        outputs.file(out)
        doLast {
            val f = out.get().asFile
            f.parentFile.mkdirs()
            val hostLines = hosts.joinToString("\n") { "                <data android:host=\"$it\" />" }
            // android.advertisingId false: no library declares AD_ID any more
            // (Firebase and ads-identifier are not linked), and main's own
            // declaration is removed here, so the merged manifest has none.
            val adIdBlock = if (!noAdId) "" else """
    <uses-permission android:name="com.google.android.gms.permission.AD_ID" tools:node="remove" />"""
            val linkBlock = if (hosts.isEmpty()) "" else """
    <!-- "Manage on the web" opens a Custom Tab (CustomTab in EsimNav.kt) and
         must target a BROWSER explicitly: a plain VIEW of one of these hosts
         would come straight back to this app once the links verify. API 30+
         only sees the browsers it declares. -->
    <queries>
        <intent>
            <action android:name="android.support.customtabs.action.CustomTabsService" />
        </intent>
        <intent>
            <action android:name="android.intent.action.VIEW" />
            <category android:name="android.intent.category.BROWSABLE" />
            <data android:scheme="https" />
        </intent>
    </queries>
    <application>
        <activity android:name="com.rostamvpn.android.MainActivity" android:exported="true" tools:node="merge">
            <intent-filter android:autoVerify="true">
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="https" />
$hostLines
                <data android:path="/esim" />
                <data android:pathPrefix="/esim/" />
                <data android:path="/number" />
                <data android:pathPrefix="/number/" />
                <data android:path="/me" />
                <data android:path="/login" />
                <data android:path="/account" />
                <data android:path="/buy" />
                <data android:path="/c/claim" />
                <data android:path="/app/open" />
            </intent-filter>
        </activity>
    </application>"""
            f.writeText("""<?xml version="1.0" encoding="utf-8"?>
<!-- GENERATED by app/build.gradle.kts from brands/$brand.json (`deepLinkHosts`, `android.advertisingId`). Do not edit. -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">$adIdBlock$linkBlock
</manifest>
""")
        }
    }
    listOf("Play", "Direct").forEach { store ->
        android.sourceSets.maybeCreate("$brand$store").manifest.srcFile(out.get().asFile)
    }
    tasks.named("preBuild") { dependsOn(gen) }
}

// Kotlin 2.x removed the String-typed `kotlinOptions.jvmTarget`; the JVM target
// now lives in the typed compilerOptions DSL. It must stay 17 to match
// compileOptions above -- a mismatch between the Java and Kotlin targets fails
// the build with an unhelpful "inconsistent JVM target" error.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // A mediation adapter (via a `+` version) can transitively pull a newer
    // kotlin-stdlib than this project's Kotlin compiler (1.9.25) can read —
    // observed kotlin-stdlib 2.3.0 → "incompatible metadata version" at compile.
    // Force the stdlib to the compiler's own line so an ad SDK bump can never
    // break the app build. Bump this in lockstep if the Kotlin plugin is upgraded.
    configurations.all {
        resolutionStrategy {
            // The Kotlin-1.9 metadata pins that used to live here are GONE.
            // Every one of them existed because a `+` mediation adapter pulled an
            // artifact compiled with newer Kotlin metadata than the 1.9.25 compiler
            // could read ("actual metadata version is 2.1.0 ... can read up to
            // 2.0.0"). On Kotlin 2.4 that failure mode does not exist, so pinning
            // the stdlib, coroutines and the ads SDK backwards is now pure downside.
            // If a future adapter ever outruns the compiler again, pin THAT artifact
            // here -- do not reintroduce a blanket stdlib force.
        }
    }

    // Jetpack Compose
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // AndroidX
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.core:core-splashscreen:1.2.0")

    // Material (for VPN services that may use legacy views)
    implementation("com.google.android.material:material:1.14.0")

    // Google Play Billing (subscriptions) — required by Play for in-app digital goods.
    implementation("com.android.billingclient:billing-ktx:8.0.0")

    // Play Install Referrer: the gclid Google writes into a Play install that came
    // from an ad (billing/InstallReferrer.kt, docs/ATTRIBUTION-2026-10-25.md). No
    // permission, no advertising id, no Google Play services dependency — it
    // binds to the Play Store app. PINNED like everything else here.
    implementation("com.android.installreferrer:installreferrer:2.2")

    // Adapty: remote paywalls. PINNED, never "+" -- a floating ad/SDK version is
    // what broke the release build in §19, and a failed Gradle build leaves the
    // PREVIOUS apk in place so the next install silently ships stale code.
    // 4.1.x matches the iOS 4.1.3 generation, and its own billing requirement is
    // 8.0.0, which is exactly what Play forced us onto on 2026-09-15.
    // PINNED. A floating "+" on an ad/analytics dependency is what broke
    // assembleRostamPlayRelease on an untouched tree (§19).
    // Per brand: a brand with `android.advertisingId: false` links no Firebase
    // (it pulls play-services-ads-identifier and declares AD_ID).
    allBrands.filter { it !in noAdIdBrands }.forEach { brand ->
        add("${brand}Implementation", "com.google.firebase:firebase-analytics:22.1.2")
    }
    // Per brand, for the same reason as Firebase above: only a brand with an
    // `adaptyPublicKey` links the SDK. rostam, goglobe and internetpro have
    // none, so for them these are not "inert at runtime" -- they are absent.
    // redvpn and dollarvpn are `adaptyOnly` with live keys and paying users;
    // they are untouched.
    adaptyBrands.forEach { brand ->
        add("${brand}Implementation", "io.adapty:android-sdk:4.1.0")
        add("${brand}Implementation", "io.adapty:android-ui:4.1.1")
    }

    // ── Adtech, PER BRAND ────────────────────────────────────────────────────
    // These were plain `implementation`, so every brand linked the ad SDK even
    // with ads switched off in config — and the SDK is what contributes
    // `com.google.android.gms.permission.AD_ID`. Play's advertising-id
    // declaration asks about the permission, not about whether an ad renders,
    // so a no-ads brand still had to answer "yes, uses advertising id".
    //
    // `adtechBrands` is filled from each brand manifest's `android.adtech`,
    // absent = true, so every brand that existed before this is unchanged.
    // A brand NOT in the set compiles against src/noAds instead, whose stubs
    // mirror these classes' signatures — so no shared Kotlin changes and there
    // is still no `if (brand == …)` anywhere (§6b).
    // Advertising ID (GAID) — EVERY BRAND, ads or not.
    //
    // `play-services-ads-identifier` is an identifier client, not an ad SDK.
    // It is kept separate from the `adtech` switch so that turning ads off for
    // a brand does not also remove the identifier that fraud checks read.
    //
    // What `adtech` gates now is the AD SDK alone: play-services-ads, the
    // mediation adapters, and the src/withAds sources. The AD_ID permission
    // follows this dependency, so it lives in src/main with it.
    // Per brand since 2026-10-25: `android.advertisingId: false` (redvpn) links
    // none, and the copy Adapty would pull transitively is excluded below; its
    // AdIdRetriever is switched off (withAdIdCollectionDisabled) before it could
    // touch the class, and proguard-rules.pro tolerates the missing reference.
    allBrands.filter { it !in noAdIdBrands }.forEach { brand ->
        add("${brand}Implementation", "com.google.android.gms:play-services-ads-identifier:18.3.0")
    }

    // ── Google AdMob + mediation (full waterfall) ────────────────────────────
    // AdMob is tried FIRST on every interstitial; the cashier/SmartyAds WebView
    // card is the no-fill fallback (see AdMobInterstitial + MainScreen). The SDK
    // brings play-services-ads-identifier transitively but the explicit dep above
    // stays explicit rather than transitive.
    adtechBrands.forEach { brand ->
        add("${brand}Implementation", "com.google.android.gms:play-services-ads:25.4.0")
    }
    // Mediation adapters — each earns only once its network is configured in a
    // mediation group in the AdMob console; adding the lib is the app-side half.
    // Dynamic (+) versions per adapter: the network SDKs release often and their
    // exact build numbers are not memorizable, so we let Gradle resolve the
    // latest each publishes (this is the pattern in the networks' own official
    // Gradle snippets). A build lock/repro is via Gradle's dependency locking if
    // ever needed; for an ad SDK, latest-compatible is the right default.
    //
    // ── AppLovin and Pangle REMOVED (2026-09-08) ────────────────────────────
    //
    // Neither network ever approved this publisher, so both earned nothing while
    // costing more than every other adapter combined. They were also the ONLY
    // two mediation SDKs shipping native code: eight .so files, ~1.9 MB of
    // arm64 machine code running in the same process as the tunnel.
    //
    //   AppLovin  libapplovin-native-crash-reporter.so            0.84 MB
    //   Pangle    libtt_ugen_layout / libnms / libpglarmor /
    //             libapminsighta / libapminsightb / libbuffer_pgl /
    //             libfile_lock_pgl                                ~1.02 MB
    //
    // Measured: the whole mediation set is 16.5 MB of a 26 MB APK. For a VPN
    // downloaded over a censored, metered, often slow connection, size is a
    // usability feature, not vanity.
    //
    // Removing an adapter is reversible and costs nothing while the network is
    // unapproved: an adapter earns only once its network is configured with real
    // credentials in an AdMob mediation group. Re-add the line if either
    // approves. Note AppLovin was pinned to 12.6.x because 13.x raises minSdk to
    // 24 and would drop Android 6.0 devices; keep that pin if it comes back.
    // ── ONLY the two networks that actually pay us ──────────────────────────
    //
    // Seven adapters removed 2026-09-08: mintegral, ironsource, vungle, fyber,
    // chartboost, moloco, pubmatic. None of them was approved, so none earned
    // anything, while every one shipped an SDK that collects device data and
    // runs in the same process as the tunnel. Moloco brought its own protobuf
    // and a metrics library; PubMatic ships a crashanalytics module. For an app
    // whose users are in Iran, unpaid telemetry is a cost with no upside.
    //
    // ironSource went with them for a second reason: Unity now owns it, so it
    // resolves to com.unity3d.ads-mediation and quietly reintroduced the Unity
    // code that was just removed.
    //
    // PINNED, not floating. The `+` versions are what broke this build: the
    // Unity adapter published 4.20.0.1 referencing com.unity3d.ads.* classes it
    // no longer ships, R8 failed, and a release nobody had edited stopped
    // building. A pin turns "a network broke us overnight" into a deliberate
    // upgrade. Bump these by hand.
    // ── Meta Audience Network REMOVED (2026-09-16) ──────────────────────────
    //
    // Play REJECTED com.redvpn.android versionCode 25 under Invalid Data safety
    // form: "We detected user data transmitted off devices that you have not
    // disclosed... App Activity Data Type - Apps On Device."
    //
    // It was not our code. The split-tunnel picker reads launchers via
    // `queryIntentActivities` (VpnViewModel.loadInstalledApps), keeps the
    // SELECTION in SharedPreferences and hands it to the VpnService by Intent;
    // nothing is sent anywhere, and the bundle has zero `getInstalledPackages`
    // / `getInstalledApplications` and no QUERY_ALL_PACKAGES. The adapter was
    // the only removable thing in the binary that Google's SDK Index lists as
    // collecting "Apps on device" — 4,677 `com/facebook/ads` references.
    //
    // Owner's call, and it is the right one for THIS product: the alternative
    // was declaring, on a public store page, that a censorship-circumvention
    // VPN collects and shares its users' installed-app list with advertisers.
    // That is a worse thing to be true than the fill is worth.
    //
    // Re-adding it means re-adding the declaration. Do not do one without the
    // other.
    // Unity REMOVED (2026-09-08), and it was not only size: it BROKE THE RELEASE
    // BUILD. The `+` resolved to adapter 4.20.0.1, which pulls
    // com.unity3d.ads-mediation:mediation-sdk but still references the old
    // com.unity3d.ads.* classes, so R8 failed with ~10 "Missing class" errors
    // and `assembleRostamPlayRelease` could not complete. Verified against the
    // untouched file: this predates the AppLovin/Pangle removal.
    //
    // That is the hazard of `+` on an ad SDK. A network can publish an adapter
    // overnight and break a release you did not change, and because a failed
    // Gradle build leaves the PREVIOUS APK in place, the next `adb install`
    // reports Success while flashing stale code (CLAUDE.md §6). Pin a version
    // if Unity is ever re-added.
    // Second wave of demand. Same rule as above: the adapter here is only the
    // app-side half — each one earns nothing until its network is added to a
    // mediation group in the AdMob console with real credentials.
    // InMobi REMOVED (2026-09-08). It cost 2 MB of APK and dragged in 31
    // transitive artifacts for one network: the entire androidx.media3
    // (ExoPlayer) video stack, plus androidx.biometric, browser, credentials
    // and exifinterface. A VPN does not need a video player, and an ad SDK
    // reaching for biometric and EXIF libraries inside the app that carries
    // the tunnel is a data-collection surface, not a feature.
    //
    // Meta is the only mediation network left. Everything else was removed on
    // the same day for the same reason: taking user data while paying nothing.
    // Chartboost and Moloco are BACK. Both were excluded only because their SDKs
    // (and, for Moloco, its Ktor 3.x / coroutines 1.10 chain) are compiled with
    // Kotlin 2.x metadata that the old 1.9.25 compiler could not read -- an
    // artifact-level block that no dependency force could work around. The
    // Kotlin 2.4 toolchain reads them, so the only thing that kept two demand
    // sources off the waterfall is gone. Each still earns nothing until its
    // network is configured in a mediation group in the AdMob console.

    // Credential Manager REMOVED 2026-09-16 with in-app sign-in. There is no
    // account any more: a subscription is held by the store account that bought
    // it, so restore is queryPurchases. Play rejected 453 reading the account
    // screen as a login wall.

    // Google Code Scanner — no CAMERA permission, uses Play Services UI
    // play-services-code-scanner REMOVED with the QR screen (2026-09-08). It was
    // the ONLY thing pulling com.google.mlkit onto the classpath (barcode-scanning
    // -common, vision-common, common), so removing the feature removes the whole
    // ML Kit stack rather than leaving an unused runtime in the app.

    // Stripe PaymentSheet: the native eSIM checkout's card rail (POST
    // /v1/orders returns the publishable key and the PaymentIntent secret).
    // PER BRAND, only `navigation: tabs` brands that sell eSIM (cardSheetBrands),
    // and PINNED: a floating version is what broke a release in §19.
    cardSheetBrands.forEach { brand ->
        add("${brand}Implementation", "com.stripe:paymentsheet:23.24.0")
    }

    // ZXing for QR code generation (display QR to share config)
    implementation("com.google.zxing:core:3.5.4")

    // Play In-App Review — renders the rating card as an overlay INSIDE the app,
    // so the user never leaves. See ReviewManager.kt for the policy.
    //
    // This is the narrow `review` artifact, NOT `review-ktx` and NOT the old
    // monolithic `com.google.android.play:core`. A predecessor of this file
    // carried review-ktx and it was pulled "to reduce dependencies and AV false
    // positives" (see the ReviewManager stub it left behind). That concern was
    // the MONOLITH: `play:core` bundled dynamic-feature delivery, i.e. code that
    // loads DEX at runtime, which is exactly the heuristic mobile AV engines
    // flag -- and a VPN binary that Iranian users sideload can afford a false
    // positive least of all. The 2.x split artifacts exist precisely to end
    // that: `review` has no dynamic module loading, no SplitCompat, and pulls
    // only play:core-common + the Play Services task types the app already
    // ships via play-services-ads. `review-ktx` on top of it buys one
    // coroutine-await extension we do not need -- the Task callback is two
    // lines. So: the Java artifact only, and nothing else re-enters.
    implementation("com.google.android.play:review:2.0.2")

    // WorkManager for the eSIM tab's local expiry and usage alerts. INTERNETPRO
    // ONLY: the worker lives in src/internetpro, shared code reaches it by name
    // (ui/screens/EsimNav.kt EsimAlerts), so no other brand gains the library,
    // its manifest entries or a notification permission.
    "internetproImplementation"("androidx.work:work-runtime-ktx:2.10.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    // The real org.json for JVM unit tests: android.jar's is a stub that throws,
    // so the e-sim.net parsers could not be checked against a captured body.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.1.4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.0")
}

// Resolve NDK path from environment or default location
// The Rust libs (libnetcore / libwgtcp_core) are built OUT of band by
// cargo-ndk and committed straight into jniLibs -- see CLAUDE.md §6. Gradle used
// to register buildRustRelease/buildRustDebug here and wire them into
// mergeJniLibFolders, pointed at an `android/` crate that no longer exists and
// passing --features chrome-tls, which is wrong for this architecture. Every
// documented build therefore had to pass `-x buildRustRelease -x buildRustDebug`
// to stop it clobbering the good libs. Removing the tasks removes the footgun.

// android.advertisingId false: nothing in the brand's graph may bring the
// advertising-id client back transitively (Adapty does, as of 4.1.0).
afterEvaluate {
    noAdIdBrands.forEach { brand ->
        configurations.matching { it.name.startsWith(brand) && (it.name.endsWith("RuntimeClasspath") || it.name.endsWith("CompileClasspath")) }
            .configureEach { exclude(group = "com.google.android.gms", module = "play-services-ads-identifier") }
    }
}
