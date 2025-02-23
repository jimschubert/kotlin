/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.cli.jvm

import com.google.common.base.Predicates.`in`
import com.intellij.openapi.Disposable
import org.jetbrains.kotlin.cli.common.CLICompiler
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.ExitCode.*
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.*
import org.jetbrains.kotlin.cli.jvm.compiler.*
import org.jetbrains.kotlin.cli.jvm.config.JVMConfigurationKeys
import org.jetbrains.kotlin.cli.jvm.config.addJavaSourceRoot
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.repl.ReplFromTerminal
import org.jetbrains.kotlin.codegen.CompilationException
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.PluginCliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.cliPluginUsageString
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.load.kotlin.incremental.components.IncrementalCompilationComponents
import org.jetbrains.kotlin.script.StandardScriptDefinition
import org.jetbrains.kotlin.util.PerformanceCounter
import org.jetbrains.kotlin.utils.KotlinPaths
import org.jetbrains.kotlin.utils.KotlinPathsFromHomeDir
import org.jetbrains.kotlin.utils.PathUtil
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

open class K2JVMCompiler : CLICompiler<K2JVMCompilerArguments>() {

    override fun doExecute(arguments: K2JVMCompilerArguments, services: Services, messageCollector: MessageCollector, rootDisposable: Disposable): ExitCode {
        val messageSeverityCollector = MessageSeverityCollector(messageCollector)
        val paths = if (arguments.kotlinHome != null)
            KotlinPathsFromHomeDir(File(arguments.kotlinHome))
        else
            PathUtil.getKotlinPathsForCompiler()

        messageSeverityCollector.report(CompilerMessageSeverity.LOGGING, "Using Kotlin home directory " + paths.homePath, CompilerMessageLocation.NO_LOCATION)
        PerformanceCounter.setTimeCounterEnabled(arguments.reportPerf);

        val configuration = CompilerConfiguration()
        configuration.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, messageSeverityCollector)

        if (IncrementalCompilation.isEnabled()) {
            val incrementalCompilationComponents = services.get(IncrementalCompilationComponents::class.java)
            configuration.put(JVMConfigurationKeys.INCREMENTAL_COMPILATION_COMPONENTS, incrementalCompilationComponents)
        }

        val locator = services.get(CompilerJarLocator::class.java)
        configuration.put(JVMConfigurationKeys.COMPILER_JAR_LOCATOR, locator)

        try {
            if (!arguments.noJdk) {
                configuration.addJvmClasspathRoots(PathUtil.getJdkClassesRoots())
            }
        }
        catch (t: Throwable) {
            MessageCollectorUtil.reportException(messageSeverityCollector, t)
            return INTERNAL_ERROR
        }


        try {
            PluginCliParser.loadPlugins(arguments, configuration)
        }
        catch (e: PluginCliOptionProcessingException) {
            val message = e.message + "\n\n" + cliPluginUsageString(e.pluginId, e.options)
            messageSeverityCollector.report(CompilerMessageSeverity.ERROR, message, CompilerMessageLocation.NO_LOCATION)
            return INTERNAL_ERROR
        }
        catch (e: CliOptionProcessingException) {
            messageSeverityCollector.report(CompilerMessageSeverity.ERROR, e.message!!, CompilerMessageLocation.NO_LOCATION)
            return INTERNAL_ERROR
        }
        catch (t: Throwable) {
            MessageCollectorUtil.reportException(messageSeverityCollector, t)
            return INTERNAL_ERROR
        }


        if (arguments.script) {
            if (arguments.freeArgs.isEmpty()) {
                messageSeverityCollector.report(CompilerMessageSeverity.ERROR, "Specify script source path to evaluate", CompilerMessageLocation.NO_LOCATION)
                return COMPILATION_ERROR
            }
            configuration.addKotlinSourceRoot(arguments.freeArgs.get(0))
        }
        else if (arguments.module == null) {
            for (arg in arguments.freeArgs) {
                configuration.addKotlinSourceRoot(arg)
                val file = File(arg)
                if (file.isDirectory) {
                    configuration.addJavaSourceRoot(file)
                }
            }
        }

        configuration.addJvmClasspathRoots(getClasspath(paths, arguments))

        configuration.put(JVMConfigurationKeys.MODULE_NAME, arguments.moduleName ?: JvmAbi.DEFAULT_MODULE_NAME)

        if (arguments.module == null && arguments.freeArgs.isEmpty() && !arguments.version) {
            ReplFromTerminal.run(rootDisposable, configuration)
            return ExitCode.OK
        }

        if (arguments.script) {
            configuration.add(CommonConfigurationKeys.SCRIPT_DEFINITIONS_KEY, StandardScriptDefinition)
            shouldReportPerf = false
        }

        putAdvancedOptions(configuration, arguments)

        messageSeverityCollector.report(CompilerMessageSeverity.LOGGING, "Configuring the compilation environment", CompilerMessageLocation.NO_LOCATION)
        try {
            configureEnvironment(configuration, arguments)

            val destination = arguments.destination

            val jar: File?
            val outputDir: File?
            if (destination != null) {
                val isJar = destination.endsWith(".jar")
                jar = if (isJar) File(destination) else null
                outputDir = if (isJar) null else File(destination)
            }
            else {
                jar = null
                outputDir = null
            }
            val environment: KotlinCoreEnvironment

            val friendPaths = arguments.friendPaths?.toList() ?: emptyList<String>()

            if (arguments.module != null) {
                val sanitizedCollector = FilteringMessageCollector(messageSeverityCollector, `in`(CompilerMessageSeverity.VERBOSE))
                val moduleScript = CompileEnvironmentUtil.loadModuleDescriptions(arguments.module, sanitizedCollector)

                if (outputDir != null) {
                    messageSeverityCollector.report(CompilerMessageSeverity.WARNING, "The '-d' option with a directory destination is ignored because '-module' is specified", CompilerMessageLocation.NO_LOCATION)
                }

                val directory = File(arguments.module).absoluteFile.parentFile

                val compilerConfiguration = KotlinToJVMBytecodeCompiler.createCompilerConfiguration(configuration, moduleScript.modules, directory)
                environment = createCoreEnvironment(rootDisposable, compilerConfiguration)

                if (messageSeverityCollector.anyReported(CompilerMessageSeverity.ERROR)) return COMPILATION_ERROR

                KotlinToJVMBytecodeCompiler.compileModules(environment, configuration, moduleScript.modules, directory, jar, friendPaths, arguments.includeRuntime)
            }
            else if (arguments.script) {
                val scriptArgs = arguments.freeArgs.subList(1, arguments.freeArgs.size)
                environment = createCoreEnvironment(rootDisposable, configuration)

                if (messageSeverityCollector.anyReported(CompilerMessageSeverity.ERROR)) return COMPILATION_ERROR

                return KotlinToJVMBytecodeCompiler.compileAndExecuteScript(configuration, paths, environment, scriptArgs)
            }
            else {
                environment = createCoreEnvironment(rootDisposable, configuration)

                if (messageSeverityCollector.anyReported(CompilerMessageSeverity.ERROR)) return COMPILATION_ERROR

                if (environment.getSourceFiles().isEmpty()) {
                    if (arguments.version) {
                        return OK
                    }
                    messageSeverityCollector.report(CompilerMessageSeverity.ERROR, "No source files", CompilerMessageLocation.NO_LOCATION)
                    return COMPILATION_ERROR
                }

                KotlinToJVMBytecodeCompiler.compileBunchOfSources(environment, jar, outputDir, friendPaths, arguments.includeRuntime)
            }

            if (arguments.reportPerf) {
                reportGCTime(environment.configuration)
                reportCompilationTime(environment.configuration)
                PerformanceCounter.report { s -> reportPerf(environment.configuration, s) }
            }
            return OK
        }
        catch (e: CompilationException) {
            messageSeverityCollector.report(CompilerMessageSeverity.EXCEPTION, OutputMessageUtil.renderException(e), MessageUtil.psiElementToMessageLocation(e.element))
            return INTERNAL_ERROR
        }

    }

    private fun createCoreEnvironment(rootDisposable: Disposable, configuration: CompilerConfiguration): KotlinCoreEnvironment {
        val result = KotlinCoreEnvironment.createForProduction(rootDisposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)

        if (initStartNanos != 0L) {
            val initNanos = System.nanoTime() - initStartNanos
            reportPerf(configuration, "INIT: Compiler initialized in " + TimeUnit.NANOSECONDS.toMillis(initNanos) + " ms")
            initStartNanos = 0L
        }
        return result
    }

    /**
     * Allow derived classes to add additional command line arguments
     */
    override fun createArguments(): K2JVMCompilerArguments {
        val result = K2JVMCompilerArguments()
        if (System.getenv("KOTLIN_REPORT_PERF") != null) {
            result.reportPerf = true
        }
        return result
    }

    companion object {
        private var initStartNanos = System.nanoTime()
        // allows to track GC time for each run when repeated compilation is used
        private val elapsedGCTime = hashMapOf<String, Long>()
        private var elapsedJITTime = 0L
        private var shouldReportPerf = true

        fun resetInitStartTime() {
            if (initStartNanos == 0L) {
                initStartNanos = System.nanoTime()
            }
        }

        @JvmStatic fun main(args: Array<String>) {
            CLICompiler.doMain(K2JVMCompiler(), args)
        }

        fun reportPerf(configuration: CompilerConfiguration, message: String) {
            if (!shouldReportPerf) return

            val collector = configuration[CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY]!!
            collector.report(CompilerMessageSeverity.INFO, "PERF: " + message, CompilerMessageLocation.NO_LOCATION)
        }

        fun reportGCTime(configuration: CompilerConfiguration) {
            ManagementFactory.getGarbageCollectorMXBeans().forEach {
                val currentTime = it.collectionTime
                val elapsedTime = elapsedGCTime.getOrElse(it.name) { 0 }
                val time = currentTime - elapsedTime
                reportPerf(configuration, "GC time for ${it.name} is $time ms")
                elapsedGCTime[it.name] = currentTime
            }
        }

        fun reportCompilationTime(configuration: CompilerConfiguration) {
            val bean = ManagementFactory.getCompilationMXBean() ?: return
            val currentTime = bean.totalCompilationTime
            reportPerf(configuration, "JIT time is ${currentTime - elapsedJITTime} ms")
            elapsedJITTime = currentTime
        }

        private fun putAdvancedOptions(configuration: CompilerConfiguration, arguments: K2JVMCompilerArguments) {
            configuration.put(JVMConfigurationKeys.DISABLE_CALL_ASSERTIONS, arguments.noCallAssertions)
            configuration.put(JVMConfigurationKeys.DISABLE_PARAM_ASSERTIONS, arguments.noParamAssertions)
            configuration.put(JVMConfigurationKeys.DISABLE_INLINE, arguments.noInline)
            configuration.put(JVMConfigurationKeys.DISABLE_OPTIMIZATION, arguments.noOptimize)
            configuration.put(JVMConfigurationKeys.MULTIFILE_FACADES_OPEN, arguments.multifileFacadesOpen);
            configuration.put(CLIConfigurationKeys.ALLOW_KOTLIN_PACKAGE, arguments.allowKotlinPackage);
        }

        private fun getClasspath(paths: KotlinPaths, arguments: K2JVMCompilerArguments): List<File> {
            val classpath = arrayListOf<File>()
            if (arguments.classpath != null) {
                classpath.addAll(arguments.classpath.split(File.pathSeparatorChar).map { File(it) })
            }
            if (!arguments.noStdlib) {
                classpath.add(paths.runtimePath)
            }
            return classpath
        }
    }
}

fun main(args: Array<String>) = K2JVMCompiler.main(args)
