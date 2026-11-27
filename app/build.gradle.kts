import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kyle.kitchenhub"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kyle.kitchenhub"
        minSdk = 30
        targetSdk = 30
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            ndk {
                abiFilters += (project.findProperty("kitchenhubAbis") as String? ?: "x86_64,armeabi-v7a").split(",")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                abiFilters += listOf("armeabi-v7a")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    androidResources {
        noCompress += listOf("onnx", "ttf")
    }
    packaging {
        resources {
            excludes += listOf("META-INF/INDEX.LIST", "META-INF/*.kotlin_module")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.dynamicanimation:dynamicanimation-ktx:1.0.0-alpha03")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    implementation("com.google.android.gms:play-services-home:17.12.0")
    implementation("com.google.android.gms:play-services-home-types:17.12.0")
}

abstract class MutexDefaultShimFactory : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor {
        return object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            private var hasUnlockDefault = false
            override fun visitMethod(
                access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?
            ) = super.visitMethod(access, name, descriptor, signature, exceptions).also {
                if (name == "unlock\$default") hasUnlockDefault = true
            }
            override fun visitEnd() {
                if (!hasUnlockDefault) {
                    val mv = super.visitMethod(
                        Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC,
                        "unlock\$default",
                        "(Lkotlinx/coroutines/sync/Mutex;Ljava/lang/Object;ILjava/lang/Object;)V",
                        null,
                        null
                    )
                    mv.visitCode()
                    val l0 = Label()
                    mv.visitVarInsn(Opcodes.ALOAD, 3)
                    mv.visitJumpInsn(Opcodes.IFNULL, l0)
                    mv.visitTypeInsn(Opcodes.NEW, "java/lang/UnsupportedOperationException")
                    mv.visitInsn(Opcodes.DUP)
                    mv.visitLdcInsn("Super calls with default arguments not supported in this target, function: unlock")
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/UnsupportedOperationException", "<init>", "(Ljava/lang/String;)V", false)
                    mv.visitInsn(Opcodes.ATHROW)
                    mv.visitLabel(l0)
                    val l1 = Label()
                    mv.visitVarInsn(Opcodes.ILOAD, 2)
                    mv.visitInsn(Opcodes.ICONST_1)
                    mv.visitInsn(Opcodes.IAND)
                    mv.visitJumpInsn(Opcodes.IFEQ, l1)
                    mv.visitInsn(Opcodes.ACONST_NULL)
                    mv.visitVarInsn(Opcodes.ASTORE, 1)
                    mv.visitLabel(l1)
                    mv.visitVarInsn(Opcodes.ALOAD, 0)
                    mv.visitVarInsn(Opcodes.ALOAD, 1)
                    mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "kotlinx/coroutines/sync/Mutex", "unlock", "(Ljava/lang/Object;)V", true)
                    mv.visitInsn(Opcodes.RETURN)
                    mv.visitMaxs(3, 4)
                    mv.visitEnd()
                }
                super.visitEnd()
            }
        }
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        return classData.className == "kotlinx.coroutines.sync.Mutex"
    }
}

androidComponents {
    onVariants { variant ->
        variant.instrumentation.transformClassesWith(
            MutexDefaultShimFactory::class.java,
            InstrumentationScope.ALL
        ) {}
        variant.instrumentation.setAsmFramesComputationMode(FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS)
    }
}

configurations.all {
    resolutionStrategy {
        force(
            "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.1",
            "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1"
        )
    }
}
