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

package org.jetbrains.kotlin.cli.common.arguments;

import com.sampullara.cli.Argument;
import org.jetbrains.annotations.NotNull;

public class K2JVMCompilerArguments extends CommonCompilerArguments {
    @Argument(value = "d", description = "Destination for generated class files")
    @ValueDescription("<directory|jar>")
    public String destination;

    @Argument(value = "classpath", alias = "cp", description = "Paths where to find user class files")
    @ValueDescription("<path>")
    public String classpath;

    @Argument(value = "include-runtime", description = "Include Kotlin runtime in to resulting .jar")
    public boolean includeRuntime;

    @Argument(value = "no-jdk", description = "Don't include Java runtime into classpath")
    public boolean noJdk;

    @Argument(value = "no-stdlib", description = "Don't include Kotlin runtime into classpath")
    public boolean noStdlib;

    @Argument(value = "module", description = "Path to the module file to compile")
    @ValueDescription("<path>")
    public String module;

    @Argument(value = "script", description = "Evaluate the script file")
    public boolean script;

    @Argument(value = "kotlin-home", description = "Path to Kotlin compiler home directory, used for runtime libraries discovery")
    @ValueDescription("<path>")
    public String kotlinHome;

    @Argument(value = "module-name", description = "Module name")
    public String moduleName;

    // Advanced options
    @Argument(value = "Xno-call-assertions", description = "Don't generate not-null assertion after each invocation of method returning not-null")
    public boolean noCallAssertions;

    @Argument(value = "Xno-param-assertions", description = "Don't generate not-null assertions on parameters of methods accessible from Java")
    public boolean noParamAssertions;

    @Argument(value = "Xno-optimize", description = "Disable optimizations")
    public boolean noOptimize;

    @Argument(value = "Xreport-perf", description = "Report detailed performance statistics")
    public boolean reportPerf;

    @Argument(value = "Xmultifile-facades-open", description = "Compile multifile facade classes as open")
    public boolean multifileFacadesOpen;

    @Argument(value = "Xallow-kotlin-package", description = "Allow compiling code in package 'kotlin'")
    public boolean allowKotlinPackage;

    // Paths to output directories for friend modules.
    public String[] friendPaths;

    @Override
    @NotNull
    public String executableScriptFileName() {
        return "kotlinc-jvm";
    }

}
