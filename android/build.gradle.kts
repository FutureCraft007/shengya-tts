import java.io.File
import java.security.MessageDigest
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val qwenOpenmp = providers.gradleProperty("qwen.openmp")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)
val qwenVulkan = providers.gradleProperty("qwen.vulkan")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)
val qwenOpencl = providers.gradleProperty("qwen.opencl")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)
val qwenOpenclSdkDir = providers.gradleProperty("qwen.opencl.sdkDir")
    .orElse(layout.projectDirectory.dir("../build/opencl-sdk").asFile.absolutePath)
val generatedOpenmpJniLibs = layout.buildDirectory.dir("generated/openmpJniLibs")
val sherpaAar = rootProject.layout.buildDirectory.file("dependencies/sherpa-onnx-static-link-onnxruntime-2.0.1.aar")
val prepareSherpa by tasks.registering {
    inputs.property("version", "2.0.1")
    doLast {
        val target = sherpaAar.get().asFile
        val expected = "07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575"
        fun digest(file: File): String {
            val hash=MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input -> val buffer=ByteArray(131072); while(true){val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n)} }
            return hash.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
        }
        if (!target.isFile || digest(target)!=expected) {
            target.parentFile.mkdirs()
            val temporary=File(target.parentFile,target.name+".download")
            URI("https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/"+target.name).toURL().openConnection().apply { connectTimeout=20000;readTimeout=30000 }.getInputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
            check(digest(temporary)==expected){"Sherpa-ONNX AAR checksum mismatch"}
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
tasks.matching { it.name=="preBuild" }.configureEach { dependsOn(prepareSherpa) }

val copyOpenmpLibs by tasks.registering {
    val sdkDir = providers.environmentVariable("ANDROID_HOME")
        .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
        .orElse("${System.getProperty("user.home")}\\AppData\\Local\\Android\\Sdk")
    inputs.property("qwenOpenmp", qwenOpenmp)
    outputs.dir(generatedOpenmpJniLibs)
    doLast {
        delete(generatedOpenmpJniLibs)
        if (qwenOpenmp.get()) {
            val libomp = fileTree("${sdkDir.get()}/ndk") {
                include("**/toolchains/llvm/prebuilt/*/lib/clang/*/lib/linux/aarch64/libomp.so")
            }.singleFile
            copy {
                from(libomp)
                into(generatedOpenmpJniLibs.get().dir("arm64-v8a"))
            }
        }
    }
}

android {
    namespace = "com.qwen.tts.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.qwen.tts.android.zh"
        minSdk = 31
        targetSdk = 36
        versionCode = 12
        versionName = "0.8.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "1536m"
            it.maxParallelForks = 1
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    ndkVersion = "28.2.13676358"

    defaultConfig {
        externalNativeBuild {
            cmake {
                targets += listOf("qwen3_tts_jni", "qwen_rnnoise")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DQWEN3_ANDROID_OPENMP=${if (qwenOpenmp.get()) "ON" else "OFF"}",
                    "-DQWEN3_ANDROID_VULKAN=${if (qwenVulkan.get()) "ON" else "OFF"}",
                    "-DQWEN3_ANDROID_OPENCL=${if (qwenOpencl.get()) "ON" else "OFF"}",
                    "-DOpenCL_INCLUDE_DIR=${qwenOpenclSdkDir.get()}/OpenCL-Headers",
                    "-DOpenCL_LIBRARY=${qwenOpenclSdkDir.get()}/lib/arm64-v8a/libOpenCL.so",
                )
            }
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(generatedOpenmpJniLibs.get().asFile)
        }
    }
}

tasks.matching {
    it.name.startsWith("merge") && (it.name.endsWith("NativeLibs") || it.name.endsWith("JniLibFolders"))
}.configureEach {
    dependsOn(copyOpenmpLibs)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(files(sherpaAar).builtBy(prepareSherpa))
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
