/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.language.cpp.plugins;

import org.gradle.api.Action;
import org.gradle.api.Incubating;
import org.gradle.api.Plugin;
import org.gradle.api.artifacts.ConfigurablePublishArtifact;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.attributes.Usage;
import org.gradle.api.file.DirectoryVar;
import org.gradle.api.file.RegularFile;
import org.gradle.api.internal.file.FileOperations;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.util.PatternSet;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.gradle.language.cpp.CppLibrary;
import org.gradle.language.cpp.internal.DefaultCppLibrary;
import org.gradle.language.cpp.tasks.CppCompile;
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform;
import org.gradle.nativeplatform.tasks.LinkSharedLibrary;
import org.gradle.nativeplatform.toolchain.NativeToolChain;
import org.gradle.nativeplatform.toolchain.internal.NativeToolChainInternal;
import org.gradle.nativeplatform.toolchain.internal.NativeToolChainRegistryInternal;
import org.gradle.nativeplatform.toolchain.internal.PlatformToolProvider;

import javax.inject.Inject;
import java.io.File;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * <p>A plugin that produces a native library from C++ source.</p>
 *
 * <p>Assumes the source files are located in `src/main/cpp`, public headers are located in `src/main/public` and implementation header files are located in `src/main/headers`.</p>
 *
 * <p>Adds a {@link CppLibrary} extension to the project to allow configuration of the library.</p>
 *
 * @since 4.1
 */
@Incubating
public class CppLibraryPlugin implements Plugin<ProjectInternal> {
    private final FileOperations fileOperations;

    @Inject
    public CppLibraryPlugin(FileOperations fileOperations) {
        this.fileOperations = fileOperations;
    }

    @Override
    public void apply(final ProjectInternal project) {
        project.getPluginManager().apply(CppBasePlugin.class);

        TaskContainer tasks = project.getTasks();
        ConfigurationContainer configurations = project.getConfigurations();
        DirectoryVar buildDirectory = project.getLayout().getBuildDirectory();
        ObjectFactory objectFactory = project.getObjects();
        ProviderFactory providers = project.getProviders();

        // TODO - extract some common code to setup the compile task and conventions

        // Add the component extension
        final CppLibrary component = project.getExtensions().create(CppLibrary.class, "library", DefaultCppLibrary.class, fileOperations);

        // Wire in dependencies
        component.getCompileIncludePath().from(configurations.getByName(CppBasePlugin.CPP_INCLUDE_PATH));

        // Add a compile task
        CppCompile compile = tasks.create("compileCpp", CppCompile.class);
        compile.includes(component.getCompileIncludePath());
        compile.source(component.getCppSource());

        compile.setCompilerArgs(Collections.<String>emptyList());
        compile.setPositionIndependentCode(true);
        compile.setMacros(Collections.<String, String>emptyMap());

        compile.setObjectFileDir(buildDirectory.dir("main/objs"));

        DefaultNativePlatform currentPlatform = new DefaultNativePlatform("current");
        compile.setTargetPlatform(currentPlatform);

        // TODO - make this lazy
        NativeToolChain toolChain = project.getModelRegistry().realize("toolChains", NativeToolChainRegistryInternal.class).getForPlatform(currentPlatform);
        final PlatformToolProvider platformToolChain = ((NativeToolChainInternal) toolChain).select(currentPlatform);
        compile.setToolChain(toolChain);

        // Add a link task
        final LinkSharedLibrary link = tasks.create("linkMain", LinkSharedLibrary.class);
        link.source(compile.getObjectFileDirectory().getAsFileTree().matching(new PatternSet().include("**/*.obj", "**/*.o")));
        link.lib(configurations.getByName(CppBasePlugin.NATIVE_LINK));
        link.setLinkerArgs(Collections.<String>emptyList());
        // TODO - need to set basename and soname
        Provider<RegularFile> linkFile = buildDirectory.file(platformToolChain.getSharedLibraryLinkFileName("lib/" + project.getName()));
        Provider<RegularFile> runtimeFile = buildDirectory.file(platformToolChain.getSharedLibraryName("lib/" + project.getName()));
        link.setOutputFile(runtimeFile);
        link.setTargetPlatform(currentPlatform);
        link.setToolChain(toolChain);

        tasks.getByName(LifecycleBasePlugin.ASSEMBLE_TASK_NAME).dependsOn(link);

        // TODO - add lifecycle tasks

        Configuration apiElements = configurations.create("cppApiElements");
        apiElements.setCanBeResolved(false);
        apiElements.getAttributes().attribute(Usage.USAGE_ATTRIBUTE, objectFactory.named(Usage.class, Usage.C_PLUS_PLUS_API));
        // TODO - deal with more than one header dir, e.g. generated public headers
        Provider<File> publicHeaders = providers.provider(new Callable<File>() {
            @Override
            public File call() throws Exception {
                Set<File> files = component.getPublicHeaderDirs().getFiles();
                if (files.size() != 1) {
                    throw new UnsupportedOperationException(String.format("The C++ library plugin currently requires exactly one public header directory, however there are %d directories are configured: %s", files.size(), files));
                }
                return files.iterator().next();
            }
        });
        apiElements.getOutgoing().artifact(publicHeaders);

        Configuration implementation = configurations.getByName(CppBasePlugin.IMPLEMENTATION);

        Configuration linkElements = configurations.create("linkElements");
        linkElements.extendsFrom(implementation);
        linkElements.setCanBeResolved(false);
        linkElements.getAttributes().attribute(Usage.USAGE_ATTRIBUTE, objectFactory.named(Usage.class, Usage.NATIVE_LINK));
        // TODO - should reflect changes to task output file
        linkElements.getOutgoing().artifact(linkFile, new Action<ConfigurablePublishArtifact>() {
            @Override
            public void execute(ConfigurablePublishArtifact artifact) {
                artifact.builtBy(link);
            }
        });

        Configuration runtimeElements = configurations.create("runtimeElements");
        runtimeElements.extendsFrom(implementation);
        runtimeElements.setCanBeResolved(false);
        runtimeElements.getAttributes().attribute(Usage.USAGE_ATTRIBUTE, objectFactory.named(Usage.class, Usage.NATIVE_RUNTIME));
        runtimeElements.getOutgoing().artifact(link.getBinaryFile());
    }
}
