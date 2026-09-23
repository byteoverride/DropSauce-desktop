import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
	alias(libs.plugins.kotlin.jvm)
	alias(libs.plugins.compose.compiler)
	alias(libs.plugins.compose.multiplatform)
	alias(libs.plugins.kotlinx.serialization)
}

/**
 * The one place the app's version is written.
 *
 * Both the package and the running app need it: the package so a .deb carries a version,
 * and the app so it can tell whether a published release is newer than itself. Two
 * hand-kept copies would drift, and the failure is silent, so it is declared here and
 * fed to both.
 */
val appVersion = "0.9.14"

/**
 * Puts [appVersion] on the classpath, so the app can read its own version at runtime.
 *
 * A generated resource rather than the jar manifest: `:desktop:run` launches from a
 * classes directory with no manifest at all, so a manifest-based version would work in
 * the package and report nothing in development, which is where the update check gets
 * tested.
 */
val generateVersionResource by tasks.registering {
	val version = appVersion
	val outputDir = layout.buildDirectory.dir("generated/appVersion")
	inputs.property("appVersion", version)
	outputs.dir(outputDir)
	doLast {
		val file = outputDir.get().asFile.resolve("dropsauce-version.properties")
		file.parentFile.mkdirs()
		file.writeText("version=$version\n")
	}
}

sourceSets.named("main") {
	resources.srcDir(generateVersionResource)
}

kotlin {
	jvmToolchain(21)
	compilerOptions {
		// Mirrors :app, which opts in once globally rather than per file. Proving this
		// resolves on CMP is the point of using MaterialExpressiveTheme below.
		freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
	}
}

dependencies {
	implementation(project(":shared"))
	implementation(compose.desktop.currentOs)
	implementation(compose.foundation)
	// CMP's material3 is version-decoupled from CMP and its bundled 1.9.0 keeps the
	// Material 3 Expressive APIs internal. See DECISIONS.md D6a.
	implementation(libs.compose.material3.cmp)
	implementation(libs.kotlinx.coroutines.core)
	// Two feature areas independently hand-wrote a JSON codec because this was missing.
	implementation(libs.kotlinx.serialization.json)

	// The source catalogue. Pure JVM jar, 1270 parsers. Unlike :app we must NOT exclude
	// org.json: Android ships it in the platform, the JVM does not. See DECISIONS.md D1.
	implementation("com.github.YakaTeam:kotatsu-parsers:${libs.versions.parsers.get()}")

	implementation(libs.androidx.sqlite.bundled)
	implementation(libs.okhttp)
	implementation(libs.okio)

	// kotlin.test alongside JUnit: :shared uses it, so a feature area moving logic
	// between the two modules should not have to rewrite its assertions.
	testImplementation(kotlin("test"))
	testImplementation(libs.junit)
	testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
	// Forward -Dlive=true to the test JVM. providers.systemProperty is the
	// configuration-cache-safe way to read it; a bare System.getProperty at configuration
	// time would be an untracked input.
	systemProperty("live", providers.systemProperty("live").getOrElse("false"))
	testLogging { showStandardStreams = true }
}

// `./gradlew :desktop:run -Pdebug` turns on printStackTraceDebug, so swallowed
// failures (a page that will not decode, a source that 403s) print instead of showing
// as a blank reader. Off by default so a normal run is quiet.
tasks.withType<JavaExec>().configureEach {
	systemProperty("dropsauce.debug", providers.gradleProperty("debug").isPresent.toString())
}

compose.desktop {
	application {
		mainClass = "org.koitharu.kotatsu.desktop.MainKt"

		nativeDistributions {
			// Both declared, neither cross-compiles. jpackage wraps the host's own
			// packaging tools, so the Compose plugin disables the task that does not
			// match the machine it is running on: packageMsi is SKIPPED on Linux and
			// packageDeb is SKIPPED on Windows. Each CI runner calls its own.
			targetFormats(TargetFormat.Deb, TargetFormat.Msi)
			// jlink ships only the modules asked for, and the default set does not
			// include this one. MemoryGuard needs it to ask the operating system how
			// much memory is left: Linux is served by /proc/meminfo and needs nothing,
			// but on Windows the bean is the only way to find out, and without the
			// module the class is simply absent, the probe returns null, and the guard
			// passes everything. That would leave the check dead on the one platform
			// whose crash reports prompted it.
			modules("jdk.management")
			// The display name, which is what a launcher shows. The deb package name
			// must stay lowercase, so linux.packageName overrides it below.
			packageName = "DropSauce"
			packageVersion = appVersion
			description = "A comic and novel reader"
			vendor = "DropSauce"
			licenseFile.set(rootProject.file("LICENSE"))

			windows {
				// Without this jpackage substitutes its own default, which is how the
				// Linux build shipped the Kotlin logo for two releases.
				iconFile.set(project.file("src/main/resources/dropsauce.ico"))
				menuGroup = "DropSauce"
				// Fixed for the life of the product, and it has to be: Windows Installer
				// identifies a product by this UUID, so changing it turns every upgrade
				// into a second copy installed alongside the first.
				upgradeUuid = "9f3d71c4-5e8a-4b2f-9c17-6a0d4e8b3f52"
				// A shortcut people can actually find, and a directory they can choose.
				menu = true
				shortcut = true
				dirChooser = true
			}

			linux {
				packageName = "dropsauce"
				menuGroup = "Graphics"
				appCategory = "Graphics"
				// Without this jpackage ships its own default, which is the Kotlin logo,
				// and every installed copy shows that in the application menu. The same
				// file is on the classpath so the running window uses it too.
				iconFile.set(project.file("src/main/resources/dropsauce.png"))
			}
		}
	}
}
