/*
 * ProGuard -- shrinking, optimization, obfuscation, and preverification
 *             of Java bytecode.
 *
 * Copyright (c) 2002-2021 Guardsquare NV
 */

package testutils

import com.tschuchort.compiletesting.KotlinCompilation
import io.kotest.core.listeners.TestListener
import io.kotest.core.spec.AutoScan
import io.kotest.core.test.TestCase
import proguard.classfile.ClassPool
import proguard.classfile.Clazz
import proguard.classfile.attribute.Attribute.RUNTIME_VISIBLE_ANNOTATIONS
import proguard.classfile.attribute.annotation.visitor.AllAnnotationVisitor
import proguard.classfile.attribute.annotation.visitor.AnnotationTypeFilter
import proguard.classfile.attribute.visitor.AllAttributeVisitor
import proguard.classfile.attribute.visitor.AttributeNameFilter
import proguard.classfile.kotlin.KotlinConstants.TYPE_KOTLIN_METADATA
import proguard.classfile.util.ClassReferenceInitializer
import proguard.classfile.util.ClassSubHierarchyInitializer
import proguard.classfile.util.ClassSuperHierarchyInitializer
import proguard.classfile.util.WarningPrinter
import proguard.classfile.util.kotlin.KotlinMetadataInitializer
import proguard.classfile.visitor.ClassPoolFiller
import proguard.io.ClassFilter
import proguard.io.ClassReader
import proguard.io.DataEntryReader
import proguard.io.FileDataEntry
import proguard.io.JarReader
import java.io.File
import java.io.PrintWriter
import java.nio.file.Files
import javax.lang.model.SourceVersion

class ClassPoolBuilder {

    companion object {
        private val compiler = KotlinCompilation()

        val libraryClassPool by lazy {
            val libraryClassPool = getJavaRuntimeClassPool(compiler.jdkHome)
            compiler.kotlinStdLibJar?.let { libraryClassPool.fromFile(it) }
            compiler.kotlinReflectJar?.let { libraryClassPool.fromFile(it) }
            return@lazy libraryClassPool
        }

        fun fromClasses(vararg clazz: Clazz): ClassPool {
            return ClassPool().apply {
                clazz.forEach(::addClass)
            }
        }

        @Suppress("UNCHECKED_CAST")
        fun fromDirectory(dir: File): ClassPool =
            fromSource(
                *Files.walk(dir.toPath())
                    .filter { it.isJavaFile() || it.isKotlinFile() }
                    .map { TestSource.fromFile(it.toFile()) }
                    .toArray() as Array<out TestSource>
            )

        fun fromFiles(vararg file: File): ClassPool =
            fromSource(*file.map { TestSource.fromFile(it) }.toTypedArray())

        fun fromSource(
            vararg source: TestSource,
            javacArguments: List<String> = emptyList(),
            kotlincArguments: List<String> = emptyList()
        ): ClassPool {
            compiler.apply {
                this.sources = source.map { it.asSourceFile() }
                this.inheritClassPath = false
                this.javacArguments = (listOf("-source", "1.8", "-target", "1.8") + javacArguments).toMutableList()
                this.kotlincArguments = kotlincArguments
                this.verbose = false
            }

            val result = compiler.compile()

            val programClassPool = ClassPool()

            val classReader: DataEntryReader = ClassReader(
                false,
                false,
                false,
                true,
                WarningPrinter(PrintWriter(System.err)),
                ClassPoolFiller(programClassPool)
            )

            result.compiledClassAndResourceFiles.filter { it.isClassFile() }.forEach {
                classReader.read(FileDataEntry(it))
            }

            if (source.count { it is KotlinSource } > 0) initializeKotlinMetadata(programClassPool)

            programClassPool.classesAccept(ClassReferenceInitializer(programClassPool, libraryClassPool))
            programClassPool.classesAccept(ClassSuperHierarchyInitializer(programClassPool, libraryClassPool))
            programClassPool.accept(ClassSubHierarchyInitializer())

            return programClassPool
        }
    }

    @AutoScan
    object LibraryClassPoolProcessingInfoCleaningListener : TestListener {
        override suspend fun beforeTest(testCase: TestCase) {
            libraryClassPool.classesAccept { clazz ->
                clazz.processingInfo = null
                clazz.processingFlags = 0
            }
        }
    }
}

internal fun isJava9OrLater(): Boolean = SourceVersion.latestSupported() > SourceVersion.RELEASE_8

internal fun getJavaRuntimeClassPool(jdkHome: File?): ClassPool {
    val libraryClassPool = ClassPool()

    if (jdkHome != null && jdkHome.exists()) {
        if (isJava9OrLater()) {
            jdkHome.resolve("jmods").listFiles().filter {
                it.name == "java.base.jmod"
            }.forEach {
                libraryClassPool.fromFile(it)
            }
        } else {
            val runtimeJar = if (jdkHome.resolve("jre").exists()) {
                jdkHome.resolve("jre").resolve("lib").resolve("rt.jar")
            } else {
                jdkHome.resolve("lib").resolve("rt.jar")
            }

            libraryClassPool.fromFile(runtimeJar)
        }
    }

    return libraryClassPool
}

internal fun ClassPool.fromFile(file: File) {
    val classReader: DataEntryReader = ClassReader(
        true,
        false,
        false,
        true,
        WarningPrinter(PrintWriter(System.err)),
        ClassPoolFiller(this)
    )

    JarReader(ClassFilter(classReader)).read(FileDataEntry(file))
}

internal fun initializeKotlinMetadata(classPool: ClassPool) {
    val kotlinMetadataInitializer =
        AllAttributeVisitor(
            AttributeNameFilter(
                RUNTIME_VISIBLE_ANNOTATIONS,
                AllAnnotationVisitor(
                    AnnotationTypeFilter(
                        TYPE_KOTLIN_METADATA,
                        KotlinMetadataInitializer(WarningPrinter(PrintWriter(System.err)))
                    )
                )
            )
        )

    classPool.classesAccept(kotlinMetadataInitializer)
}