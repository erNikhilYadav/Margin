import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.1"
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("net.java.dev.jna:jna:5.14.0") // AppKit window behaviour for the menu-bar mode
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(17) }

compose.desktop {
    application {
        mainClass = "margin.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "Margin"
            packageVersion = "0.0.1"
            macOS {
                bundleID = "com.margin.app"
                iconFile.set(project.file("packaging/Margin.icns"))
            }
        }
    }
}
