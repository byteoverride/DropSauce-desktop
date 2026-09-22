import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
	alias(libs.plugins.kotlin.jvm)
	alias(libs.plugins.compose.compiler)
	alias(libs.plugins.compose.multiplatform)
	alias(libs.plugins.kotlinx.serialization)
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
			targetFormats(TargetFormat.Deb)
			packageName = "dropsauce"
			packageVersion = "0.9.7"
			description = "A comic and novel reader"
			vendor = "DropSauce"
			licenseFile.set(rootProject.file("LICENSE"))

			linux {
				menuGroup = "Graphics"
				appCategory = "Graphics"
			}
		}
	}
}
