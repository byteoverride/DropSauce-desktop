import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	alias(libs.plugins.kotlin.multiplatform)
	// NOT com.android.library: since AGP 9.0 that plugin cannot be combined with
	// kotlin.multiplatform. See DECISIONS.md D6.
	alias(libs.plugins.android.kotlin.multiplatform.library)
	alias(libs.plugins.ksp)
	alias(libs.plugins.room)
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
			api(libs.androidx.room.runtime)
			implementation(libs.androidx.sqlite.bundled)
		}
		getByName("commonTest").dependencies {
			implementation(kotlin("test"))
			implementation(libs.kotlinx.coroutines.test)
		}
		getByName("jvmTest").dependencies {
			// A plain JDBC driver, used only to build a previous-version database by hand
			// so the migration can be tested against it.
			implementation("org.xerial:sqlite-jdbc:3.50.1.0")
		}
	}
}

room {
	schemaDirectory("$projectDir/schemas")
}

dependencies {
	add("kspJvm", libs.androidx.room.compiler)
	add("kspAndroid", libs.androidx.room.compiler)
}
