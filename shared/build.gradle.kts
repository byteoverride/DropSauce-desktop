import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	alias(libs.plugins.kotlin.multiplatform)
	// NOT com.android.library: since AGP 9.0 that plugin cannot be combined with
	// kotlin.multiplatform. See DECISIONS.md D6.
	alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
	jvmToolchain(21)

	android {
		namespace = "org.koitharu.kotatsu.shared"
		compileSdk = 37
		minSdk = 26

		// Load-bearing. :app compiles to JvmTarget.JVM_11. Left unpinned, this
		// compilation follows jvmToolchain(21) and emits Java 21 bytecode, and
		// :app:assembleDebug still passes, so the divergence is silent.
		compilations.configureEach {
			compileTaskProvider.configure {
				compilerOptions {
					jvmTarget.set(JvmTarget.JVM_11)
				}
			}
		}
	}

	jvm()

	sourceSets {
		getByName("commonMain").dependencies {
			implementation(libs.kotlinx.coroutines.core)
			api(libs.okio)
		}
		getByName("commonTest").dependencies {
			implementation(kotlin("test"))
		}
	}
}
