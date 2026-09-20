import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
	alias(libs.plugins.kotlin.jvm)
	alias(libs.plugins.compose.compiler)
	alias(libs.plugins.compose.multiplatform)
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
}

compose.desktop {
	application {
		mainClass = "org.koitharu.kotatsu.desktop.MainKt"

		nativeDistributions {
			targetFormats(TargetFormat.Deb)
			packageName = "dropsauce"
			packageVersion = "0.9.6"
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
