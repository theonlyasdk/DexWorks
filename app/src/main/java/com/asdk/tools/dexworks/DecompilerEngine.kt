package com.asdk.tools.dexworks

import com.googlecode.d2j.Method
import com.googlecode.d2j.dex.Dex2jar
import com.googlecode.d2j.dex.DexExceptionHandler
import com.googlecode.d2j.node.DexMethodNode
import kotlinx.coroutines.CancellationException
import org.benf.cfr.reader.api.CfrDriver
import org.benf.cfr.reader.api.OutputSinkFactory
import org.benf.cfr.reader.api.SinkReturns
import org.jf.baksmali.Baksmali
import org.jf.baksmali.BaksmaliOptions
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.objectweb.asm.MethodVisitor
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object DecompilerEngine {

    enum class Format {
        SMALI,
        JAVA
    }

    data class DecompileConfig(
        val apkFile: File,
        val dexEntryPath: String,
        val outputDir: File,
        val format: Format,
        val smaliRegInfo: Boolean = false,
        val smaliLocals: Boolean = false,
        val javaSugar: Boolean = true,
        val javaDeobfuscate: Boolean = false,
        val deleteApkAfterDecompile: Boolean = false
    )

    fun decompile(
        config: DecompileConfig,
        onLog: (String) -> Unit
    ): Boolean {
        if (!config.apkFile.isFile) {
            onLog("Error: APK file is missing.")
            return false
        }
        if (config.dexEntryPath.isBlank() || !config.dexEntryPath.endsWith(".dex", ignoreCase = true)) {
            onLog("Error: The selected entry is not a DEX file.")
            return false
        }
        if (config.outputDir.exists() && !config.outputDir.deleteRecursively()) {
            onLog("Error: Could not clear the previous output directory.")
            return false
        }
        if (!config.outputDir.mkdirs()) {
            onLog("Error: Could not create the output directory.")
            return false
        }
        val tempDexFile = File(
            config.outputDir.parentFile ?: config.outputDir,
            "temp_${System.currentTimeMillis()}_${File(config.dexEntryPath).name}"
        )
        try {
            onLog("Extracting ${config.dexEntryPath}...")
            extractDex(config.apkFile, config.dexEntryPath, tempDexFile)
            checkInterrupted()
            if (!tempDexFile.exists() || tempDexFile.length() == 0L) {
                onLog("Error: Failed to extract ${config.dexEntryPath}")
                return false
            }

            return when (config.format) {
                Format.SMALI -> decompileSmali(config, tempDexFile, onLog)
                Format.JAVA -> decompileJava(config, tempDexFile, onLog)
            }
        } catch (cancelled: CancellationException) {
            onLog("Decompilation cancelled.")
            throw cancelled
        } catch (e: Exception) {
            onLog("Decompilation error: ${e.message ?: e.javaClass.simpleName}")
            return false
        } finally {
            tempDexFile.delete()
            if (config.deleteApkAfterDecompile) config.apkFile.delete()
        }
    }

    private fun extractDex(apkFile: File, entryPath: String, dest: File) {
        ZipFile(apkFile).use { zip ->
            val entry = zip.getEntry(entryPath)
                ?: throw IllegalArgumentException("Entry $entryPath not found in APK")
            zip.getInputStream(entry).use { input ->
                FileOutputStream(dest).use { output ->
                    copyInterruptibly(input, output)
                }
            }
        }
    }

    private fun decompileSmali(
        config: DecompileConfig,
        dexFile: File,
        onLog: (String) -> Unit
    ): Boolean {
        onLog("Initializing baksmali disassembler...")
        val dex = DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
        val options = BaksmaliOptions().apply {
            localsDirective = config.smaliLocals
            if (config.smaliRegInfo) {
                registerInfo = BaksmaliOptions.ALL
            }
        }
        val jobs = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        onLog("Disassembling classes to smali using $jobs workers...")
        checkInterrupted()
        val success = Baksmali.disassembleDexFile(dex, config.outputDir, jobs, options)
        checkInterrupted()
        val fileCount = config.outputDir.walkTopDown().count { it.isFile && it.extension == "smali" }
        onLog(if (success) "Disassembly finished: $fileCount Smali files." else "Baksmali completed with errors.")
        return success && fileCount > 0
    }

    private fun decompileJava(
        config: DecompileConfig,
        dexFile: File,
        onLog: (String) -> Unit
    ): Boolean {
        onLog("Initializing CFR Java decompiler...")
        val options = mutableMapOf<String, String>()
        options["outputdir"] = config.outputDir.absolutePath
        if (!config.javaSugar) {
            options["decodeenumswitch"] = "false"
            options["decodestringswitch"] = "false"
            options["decodelambdas"] = "false"
            options["tryresources"] = "false"
            options["sugarenums"] = "false"
            options["sugarboxing"] = "false"
            options["sugarasserts"] = "false"
        }
        if (config.javaDeobfuscate) {
            options["rename"] = "true"
        }

        val classDirectory = File(config.outputDir.parentFile, "classes_${System.currentTimeMillis()}")
        val intermediateJar = File(config.outputDir.parentFile, "classes_${System.currentTimeMillis()}.jar")
        val translationErrors = AtomicInteger()
        try {
            classDirectory.mkdirs()
            onLog("Converting DEX bytecode to JVM classes...")
            Dex2jar.from(dexFile)
                .withExceptionHandler(object : DexExceptionHandler {
                    override fun handleFileException(e: Exception) {
                        translationErrors.incrementAndGet()
                        onLog("DEX conversion warning: ${e.message ?: e.javaClass.simpleName}")
                    }

                    override fun handleMethodTranslateException(
                        method: Method,
                        methodNode: DexMethodNode,
                        mv: MethodVisitor,
                        e: Exception
                    ) {
                        translationErrors.incrementAndGet()
                        onLog("Could not convert ${method.name}: ${e.message ?: e.javaClass.simpleName}")
                    }
                })
                .to(classDirectory.toPath())
            checkInterrupted()

            val classFiles = classDirectory.walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .toList()
            if (classFiles.isEmpty()) {
                onLog("Error: DEX conversion produced no JVM classes.")
                return false
            }
            onLog("Converted ${classFiles.size} classes; packaging for CFR...")
            writeJar(classDirectory, classFiles, intermediateJar)
            checkInterrupted()

            return decompileClasses(config, intermediateJar, translationErrors.get(), onLog)
        } finally {
            classDirectory.deleteRecursively()
            intermediateJar.delete()
        }
    }

    private fun decompileClasses(
        config: DecompileConfig,
        classJar: File,
        translationErrorCount: Int,
        onLog: (String) -> Unit
    ): Boolean {
        val options = mutableMapOf(
            "outputdir" to config.outputDir.absolutePath,
            "silent" to "true"
        )

        val sinkFactory = object : OutputSinkFactory {
            override fun getSupportedSinks(
                sinkType: OutputSinkFactory.SinkType?,
                available: MutableCollection<OutputSinkFactory.SinkClass>?
            ): MutableList<OutputSinkFactory.SinkClass> {
                return if (sinkType == OutputSinkFactory.SinkType.JAVA) {
                    mutableListOf(OutputSinkFactory.SinkClass.DECOMPILED, OutputSinkFactory.SinkClass.STRING)
                } else {
                    mutableListOf(OutputSinkFactory.SinkClass.STRING)
                }
            }

            @Suppress("UNCHECKED_CAST")
            override fun <T : Any?> getSink(
                sinkType: OutputSinkFactory.SinkType?,
                sinkClass: OutputSinkFactory.SinkClass?
            ): OutputSinkFactory.Sink<T> {
                if (sinkType == OutputSinkFactory.SinkType.JAVA && sinkClass == OutputSinkFactory.SinkClass.DECOMPILED) {
                    return OutputSinkFactory.Sink<SinkReturns.Decompiled> { decompiled ->
                        checkInterrupted()
                        val packagePath = decompiled.packageName.replace('.', File.separatorChar)
                        val dir = if (packagePath.isBlank()) config.outputDir else File(config.outputDir, packagePath)
                        dir.mkdirs()
                        val targetFile = File(dir, "${decompiled.className}.java")
                        targetFile.writeText(decompiled.java)
                        val displayPkg = if (decompiled.packageName.isBlank()) "" else "${decompiled.packageName}."
                        onLog("Decompiled $displayPkg${decompiled.className}.java")
                    } as OutputSinkFactory.Sink<T>
                }
                return OutputSinkFactory.Sink<T> { message ->
                    if (message != null && sinkType != OutputSinkFactory.SinkType.JAVA) {
                        val text = message.toString().trim()
                        if (text.isNotBlank()) {
                            onLog(text)
                        }
                    }
                }
            }
        }

        val driver = CfrDriver.Builder()
            .withOptions(options)
            .withOutputSink(sinkFactory)
            .build()

        onLog("Decompiling JVM classes to Java...")
        checkInterrupted()
        driver.analyse(listOf(classJar.absolutePath))
        checkInterrupted()
        val fileCount = config.outputDir.walkTopDown().count { it.isFile && it.extension == "java" }
        onLog("CFR produced $fileCount Java files${if (translationErrorCount > 0) " with $translationErrorCount conversion warnings" else ""}.")
        if (fileCount == 0) onLog("Error: No Java source files were generated.")
        return fileCount > 0
    }

    private fun writeJar(classDirectory: File, classFiles: List<File>, outputJar: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputJar))).use { output ->
            classFiles.forEach { classFile ->
                checkInterrupted()
                val entryName = classFile.relativeTo(classDirectory).invariantSeparatorsPath
                output.putNextEntry(ZipEntry(entryName))
                classFile.inputStream().use { input -> copyInterruptibly(input, output) }
                output.closeEntry()
            }
        }
    }

    private fun copyInterruptibly(input: java.io.InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            checkInterrupted()
            val count = input.read(buffer)
            if (count < 0) return
            output.write(buffer, 0, count)
        }
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw CancellationException("Decompilation cancelled.")
        }
    }
}
