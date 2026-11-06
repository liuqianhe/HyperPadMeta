plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    compileSdk = 33

    defaultConfig {
        applicationId = "pub.chara.hyperpadmeta"
        minSdk = 30
        targetSdk = 32
        versionCode = 13
        versionName = "3.3-hyperos3"
    }

    // 正式发布密钥：keystore/hyperpadmeta-release.jks（jks 入库，密码不入库）
    // 密码来源：keystore/pass.txt（本地，已 gitignore）或环境变量 HP_KS_PASS
    signingConfigs {
        create("release") {
            val ks = rootProject.file("keystore/hyperpadmeta-release.jks")
            val passFile = rootProject.file("keystore/pass.txt")
            if (ks.exists()) {
                storeFile = ks
                keyAlias = "hyperpadmeta"
                storePassword = passFile.takeIf { it.exists() }?.readText()?.trim()
                    ?: System.getenv("HP_KS_PASS")
                keyPassword = storePassword
            }
        }
    }

    buildTypes {
        named("release") {
            signingConfig = signingConfigs.findByName("release")
            isShrinkResources = true
            isMinifyEnabled = true
            proguardFiles("proguard-rules.pro")
        }
    }

    androidResources {
        additionalParameters("--allow-reserved-package-id", "--package-id", "0x45")
    }

    packagingOptions {
        resources {
            excludes += "/META-INF/**"
            excludes += "/kotlin/**"
            excludes += "/*.txt"
            excludes += "/*.bin"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
    }
}

dependencies {
    implementation("com.github.kyuubiran:EzXHelper:0.9.2")
    compileOnly("de.robv.android.xposed:api:82")
}
