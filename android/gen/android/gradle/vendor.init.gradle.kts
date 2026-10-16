// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

/**
 * Copies every artifact the build resolves into `local-maven/` (Maven layout)
 * so the project builds with `--offline` from the repository tree alone.
 *
 * Usage, from the Gradle project root, with the remote repositories reachable:
 *
 *     ./gradlew -I gradle/vendor.init.gradle.kts vendorDependencies
 *
 * Resolves every resolvable configuration of every project (all variants'
 * compile and runtime classpaths, kotlinCompilerClasspath, lint, aapt2, the
 * annotation processor paths) plus the buildscript classpath, and copies each
 * resolved module's files (jar/aar, .pom, .module, classifier artifacts) from
 * the Gradle module cache into `local-maven/<group path>/<name>/<version>/`.
 * A module whose raw `.pom`/`.module` the cache has evicted (Gradle keeps only
 * a binary descriptor for modules that resolve to a variant artifact such as
 * `<name>-android`) has its metadata fetched from the configured repositories,
 * and the closure of parent POMs and imported BOMs is vendored the same way.
 * Artifacts are written under the `url` their module metadata declares (the
 * cache stores them under `name`). AGP's `aapt2`, resolved through a detached
 * configuration at execution time, is added by matching the AGP version, and
 * `lint-gradle` (the same pattern) is resolved from AGP's tools-base version.
 */
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

gradle.rootProject {
    tasks.register("vendorDependencies") {
        description = "Copy all resolved dependencies into local-maven/ (Maven layout)."
        notCompatibleWithConfigurationCache("resolves every configuration at execution time")
        doLast {
            val cache = File(gradle.gradleUserHomeDir, "caches/modules-2/files-2.1")
            require(cache.isDirectory) { "module cache not found at $cache" }
            val target = rootDir.resolve("local-maven")
            val modules = sortedSetOf<String>()

            fun collect(cfg: org.gradle.api.artifacts.Configuration, owner: String) {
                if (!cfg.isCanBeResolved) return
                val result = try {
                    cfg.incoming.resolutionResult.allComponents
                } catch (e: Exception) {
                    logger.info("vendor: skip $owner:${cfg.name}: ${e.message}")
                    return
                }
                result.forEach { c ->
                    val id = c.id
                    if (id is ModuleComponentIdentifier) modules += "${id.group}:${id.module}:${id.version}"
                }
            }

            collect(rootProject.buildscript.configurations.getByName("classpath"), "buildscript")
            rootProject.allprojects.forEach { p ->
                p.buildscript.configurations.forEach { collect(it, "${p.path}:buildscript") }
                p.configurations.forEach { collect(it, p.path) }
            }
            try {
                val ver = rootProject.buildscript.classLoader.loadClass("com.android.Version")
                val base = ver.getField("ANDROID_TOOLS_BASE_VERSION").get(null) as String
                val app = rootProject.allprojects.first { it.extensions.findByName("android") != null }
                collect(app.configurations.detachedConfiguration(app.dependencies.create("com.android.tools.lint:lint-gradle:$base")), "lint-gradle")
            } catch (e: Exception) { logger.warn("vendor: lint-gradle not added: ${e.message}") }
            modules.filter { it.startsWith("com.android.tools.build:gradle:") }.map { it.substringAfterLast(':') }.forEach { agp ->
                cache.resolve("com.android.tools.build/aapt2").listFiles()
                    ?.filter { it.isDirectory && it.name.startsWith("$agp-") }
                    ?.forEach { modules += "com.android.tools.build:aapt2:${it.name}" }
            }

            val remotes = (rootProject.buildscript.repositories + rootProject.allprojects.flatMap { it.repositories })
                .filterIsInstance<org.gradle.api.artifacts.repositories.MavenArtifactRepository>()
                .map { it.url.toString().trimEnd('/') }
                .filter { it.startsWith("http") }
                .distinct()
            fun fetch(url: String, into: File): Boolean = try {
                java.net.URL(url).openStream().use { inp -> into.outputStream().use { inp.copyTo(it) } }
                true
            } catch (e: Exception) { into.delete(); false }

            var files = 0
            var fetched = 0
            var missing = 0
            modules.forEach { gav ->
                val (g, n, v) = gav.split(":")
                val src = cache.resolve(g).resolve(n).resolve(v)
                val dst = target.resolve(g.replace('.', '/')).resolve(n).resolve(v)
                dst.mkdirs()
                var havePom = false
                src.listFiles()?.filter { it.isDirectory }?.forEach { hashDir ->
                    hashDir.listFiles()?.filter { it.isFile && !it.name.endsWith("-sources.jar") && !it.name.endsWith("-javadoc.jar") }?.forEach { f ->
                        f.copyTo(dst.resolve(f.name), overwrite = true); files++
                        if (f.name == "$n-$v.pom") havePom = true
                    }
                }
                if (!havePom && !dst.resolve("$n-$v.pom").isFile) {
                    val rel = "${g.replace('.', '/')}/$n/$v/$n-$v"
                    val ok = remotes.any { r ->
                        fetch("$r/$rel.pom", dst.resolve("$n-$v.pom")).also { got ->
                            if (got) { fetched++; if (fetch("$r/$rel.module", dst.resolve("$n-$v.module"))) fetched++ }
                        }
                    }
                    if (!ok) { missing++; logger.warn("vendor: no metadata found for $gav") }
                }
            }
            fun text(e: org.w3c.dom.Element, tag: String): String? =
                e.childNodes.let { n -> (0 until n.length).map { n.item(it) } }
                    .filterIsInstance<org.w3c.dom.Element>().firstOrNull { it.tagName == tag }?.textContent?.trim()
            fun children(e: org.w3c.dom.Element, tag: String): List<org.w3c.dom.Element> =
                e.childNodes.let { n -> (0 until n.length).map { n.item(it) } }
                    .filterIsInstance<org.w3c.dom.Element>().filter { it.tagName == tag }
            fun ensure(g: String, n: String, v: String): File? {
                val dst = target.resolve(g.replace('.', '/')).resolve(n).resolve(v)
                val pom = dst.resolve("$n-$v.pom")
                if (pom.isFile) return pom
                dst.mkdirs()
                val src = cache.resolve(g).resolve(n).resolve(v)
                src.listFiles()?.filter { it.isDirectory }?.forEach { h ->
                    h.listFiles()?.filter { it.isFile && !it.name.endsWith("-sources.jar") && !it.name.endsWith("-javadoc.jar") }
                        ?.forEach { f -> f.copyTo(dst.resolve(f.name), overwrite = true); files++ }
                }
                if (pom.isFile) return pom
                val rel = "${g.replace('.', '/')}/$n/$v/$n-$v"
                val ok = remotes.any { r -> fetch("$r/$rel.pom", pom).also { got -> if (got) { fetched++; if (fetch("$r/$rel.module", dst.resolve("$n-$v.module"))) fetched++ } } }
                if (!ok) { missing++; logger.warn("vendor: no POM found for $g:$n:$v"); return null }
                return pom
            }
            val seen = mutableSetOf<File>()
            var parents = 0
            fun close(pom: File) {
                if (!seen.add(pom)) return
                val doc = try {
                    javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom).documentElement
                } catch (e: Exception) { logger.warn("vendor: unreadable POM $pom: ${e.message}"); return }
                val props = mutableMapOf<String, String>()
                val parent = children(doc, "parent").firstOrNull()
                val pg = parent?.let { text(it, "groupId") }
                val pv = parent?.let { text(it, "version") }
                props["project.groupId"] = text(doc, "groupId") ?: pg ?: ""
                props["project.version"] = text(doc, "version") ?: pv ?: ""
                props["project.parent.version"] = pv ?: ""
                children(doc, "properties").firstOrNull()?.let { ps ->
                    ps.childNodes.let { n -> (0 until n.length).map { n.item(it) } }
                        .filterIsInstance<org.w3c.dom.Element>().forEach { props[it.tagName] = it.textContent.trim() }
                }
                fun expand(s: String?): String? = s?.let { v ->
                    Regex("\\$\\{([^}]+)}").replace(v) { m -> props[m.groupValues[1]] ?: m.value }
                }
                if (parent != null) {
                    val a = text(parent, "artifactId")
                    if (pg != null && a != null && pv != null) ensure(pg, a, pv)?.let { parents++; close(it) }
                }
                children(doc, "dependencyManagement").firstOrNull()?.let { dm ->
                    children(dm, "dependencies").firstOrNull()?.let { ds ->
                        children(ds, "dependency").forEach { d ->
                            if (text(d, "scope") == "import") {
                                val g = expand(text(d, "groupId")); val a = text(d, "artifactId"); val v = expand(text(d, "version"))
                                if (g != null && a != null && v != null && !v.contains("$"))
                                    ensure(g, a, v)?.let { parents++; close(it) }
                            }
                        }
                    }
                }
            }
            target.walkTopDown().filter { it.isFile && it.name.endsWith(".pom") }.toList().forEach { close(it) }

            var renamed = 0
            target.walkTopDown().filter { it.isFile && it.name.endsWith(".module") }.toList().forEach { mod ->
                val dir = mod.parentFile
                val json = try { groovy.json.JsonSlurper().parse(mod) as Map<*, *> } catch (e: Exception) { return@forEach }
                (json["variants"] as? List<*>)?.forEach { v ->
                    ((v as? Map<*, *>)?.get("files") as? List<*>)?.forEach { f ->
                        val name = (f as? Map<*, *>)?.get("name") as? String ?: return@forEach
                        val url = f["url"] as? String ?: return@forEach
                        if (name != url && dir.resolve(name).isFile) {
                            if (dir.resolve(url).isFile) dir.resolve(name).delete()
                            else dir.resolve(name).renameTo(dir.resolve(url))
                            renamed++
                        }
                    }
                }
            }

            println("vendor: ${modules.size} modules, $parents parent/import POMs, $files files copied, $renamed renamed, " +
                "$fetched metadata files fetched -> $target" +
                (if (missing > 0) " ($missing modules without metadata)" else ""))
        }
    }
}
