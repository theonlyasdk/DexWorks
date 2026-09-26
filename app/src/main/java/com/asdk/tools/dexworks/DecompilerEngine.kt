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
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

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
        val deleteApkAfterDecompile: Boolean = false,
        val workerCount: Int = 2
    )

    /**
     * @param onProgress invoked with (done, total) file counts. Called on background
     * threads; total <= 0 means the total is not known yet (conversion phase).
     */
    fun decompile(
        config: DecompileConfig,
        onLog: (String) -> Unit,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
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
                Format.SMALI -> decompileSmali(config, tempDexFile, onLog, onProgress)
                Format.JAVA -> decompileJava(config, tempDexFile, onLog, onProgress)
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

    /**
     * Counts finished output files on a daemon thread while a blocking engine
     * call runs. Used where the engine reports no per-file progress (baksmali).
     * The caller must stop it in a finally block; it emits one last count on stop.
     */
    private class OutputPoller(
        private val outputDir: File,
        private val extension: String,
        private val total: Int,
        private val onProgress: (Int, Int) -> Unit
    ) {
        @Volatile
        private var running = true
        private val thread = Thread({
            while (running) {
                emit()
                try {
                    Thread.sleep(400)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "decompile-progress").apply { isDaemon = true }

        fun start() {
            onProgress(0, total.coerceAtLeast(0))
            thread.start()
        }

        fun stop() {
            running = false
            thread.interrupt()
            try {
                thread.join(2000)
            } catch (_: InterruptedException) {
            }
            emit()
        }

        private fun emit() {
            if (total <= 0) return
            val done = try {
                outputDir.walkTopDown().count { it.isFile && it.extension == extension }
            } catch (_: Exception) {
                return
            }
            onProgress(done.coerceIn(0, total), total)
        }
    }

    private fun decompileSmali(
        config: DecompileConfig,
        dexFile: File,
        onLog: (String) -> Unit,
        onProgress: (Int, Int) -> Unit
    ): Boolean {
        onLog("Initializing baksmali disassembler...")
        val dex = DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
        val options = BaksmaliOptions().apply {
            localsDirective = config.smaliLocals
            if (config.smaliRegInfo) {
                registerInfo = BaksmaliOptions.ALL
            }
        }
        val jobs = config.workerCount.coerceIn(1, 8)
        onLog("Disassembling classes to smali using $jobs workers...")
        // Baksmali reports no per-class progress, so observe its output directory.
        val poller = OutputPoller(config.outputDir, "smali", dex.classes.size, onProgress)
        poller.start()
        try {
            checkInterrupted()
            val success = Baksmali.disassembleDexFile(dex, config.outputDir, jobs, options)
            checkInterrupted()
            val fileCount = config.outputDir.walkTopDown().count { it.isFile && it.extension == "smali" }
            onLog(if (success) "Disassembly finished: $fileCount Smali files." else "Baksmali completed with errors.")
            return success && fileCount > 0
        } finally {
            poller.stop()
        }
    }

    private fun decompileJava(
        config: DecompileConfig,
        dexFile: File,
        onLog: (String) -> Unit,
        onProgress: (Int, Int) -> Unit
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
            onLog("Converted ${classFiles.size} classes.")
            checkInterrupted()

            // CFR folds inner classes into their outer file, so only top-level
            // classes produce output. Failed classes produce nothing, so the bar
            // can stall below full and the caller snaps it on completion.
            val totalJavaFiles = classFiles.count { !it.nameWithoutExtension.contains('$') }
            return decompileClasses(config, classFiles, translationErrors.get(), totalJavaFiles, onLog, onProgress)
        } finally {
            classDirectory.deleteRecursively()
        }
    }

    private fun decompileClasses(
        config: DecompileConfig,
        classFiles: List<File>,
        translationErrorCount: Int,
        totalJavaFiles: Int,
        onLog: (String) -> Unit,
        onProgress: (Int, Int) -> Unit
    ): Boolean {
        val options = mutableMapOf(
            "outputdir" to config.outputDir.absolutePath,
            "silent" to "true"
        )
        val completed = AtomicInteger(0)

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
                        if (totalJavaFiles > 0) {
                            onProgress(completed.incrementAndGet().coerceIn(0, totalJavaFiles), totalJavaFiles)
                        }
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

        val workers = config.workerCount.coerceIn(1, 8)
        val shards = shardByOuterClass(classFiles, workers)
        onLog("Decompiling ${classFiles.size} JVM classes to Java using ${shards.size} worker(s)...")
        if (totalJavaFiles > 0) onProgress(0, totalJavaFiles)
        checkInterrupted()
        if (shards.size == 1) {
            analyseShard(shards.first(), options, sinkFactory)
        } else {
            analyseParallel(shards, options, sinkFactory)
        }
        checkInterrupted()
        val fileCount = config.outputDir.walkTopDown().count { it.isFile && it.extension == "java" }
        onLog("CFR produced $fileCount Java files${if (translationErrorCount > 0) " with $translationErrorCount conversion warnings" else ""}.")
        if (fileCount == 0) onLog("Error: No Java source files were generated.")
        return fileCount > 0
    }

    /**
     * Splits classes into balanced shards, keeping each inner class with its
     * outer class so every shard stays self-contained for CFR.
     */
    private fun shardByOuterClass(classFiles: List<File>, shards: Int): List<List<String>> {
        val groups = classFiles.groupBy { it.nameWithoutExtension.substringBefore('$') }
        val buckets = List(shards) { mutableListOf<String>() }
        val sizes = IntArray(shards)
        groups.entries.sortedByDescending { it.value.size }.forEach { (_, files) ->
            var best = 0
            for (i in 1 until shards) {
                if (sizes[i] < sizes[best]) best = i
            }
            files.forEach { buckets[best].add(it.absolutePath) }
            sizes[best] += files.size
        }
        return buckets.filter { it.isNotEmpty() }
    }

    private fun analyseShard(
        targets: List<String>,
        options: Map<String, String>,
        sinkFactory: OutputSinkFactory
    ) {
        val driver = CfrDriver.Builder()
            .withOptions(options)
            .withOutputSink(sinkFactory)
            .build()
        driver.analyse(targets)
    }

    /**
     * One CFR driver per shard. Drivers are independent and only share the sink,
     * which writes distinct files and counts through an atomic integer.
     */
    private fun analyseParallel(
        shards: List<List<String>>,
        options: Map<String, String>,
        sinkFactory: OutputSinkFactory
    ) {
        val executor = Executors.newFixedThreadPool(shards.size)
        try {
            val futures = shards.map { shard ->
                executor.submit(Callable {
                    analyseShard(shard, options, sinkFactory)
                })
            }
            try {
                futures.forEach { it.get() }
            } catch (e: InterruptedException) {
                futures.forEach { it.cancel(true) }
                throw CancellationException("Decompilation cancelled.", e)
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        } finally {
            executor.shutdownNow()
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
