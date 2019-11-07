/*
 * Copyright 2019-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.infer;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.ExtraClasspathProvider;
import com.facebook.buck.jvm.java.JavaLibraryClasspathProvider;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.shell.DefaultShellStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * {@code #nullsafe} flavor for a Java rule that performs Infer's Nullsafe analysis for the rule.
 *
 * <p>The result of the analysis is captured in a JSON file generated by the rule.
 */
public final class InferNullsafe extends ModernBuildRule<InferNullsafe.Impl> {

  public static final Flavor INFER_NULLSAFE = InternalFlavor.of("nullsafe");

  private InferNullsafe(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      SourcePathRuleFinder ruleFinder,
      Impl buildable) {
    super(buildTarget, filesystem, ruleFinder, buildable);
  }

  /**
   * Builds {@link InferNullsafe} rule with a properly setup dependency on base library.
   *
   * <p>Requires {@code javacOptions} to have fully configured {code bootclasspath}. For Android
   * bootclasspath usually provided separately via {@code extraClasspathProviderSupplier}.
   */
  public static InferNullsafe create(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      JavacOptions javacOptions,
      Optional<ExtraClasspathProvider> extraClasspathProvider,
      UnresolvedInferPlatform unresolvedInferPlatform,
      InferConfig config) {
    BuildTarget unflavored = buildTarget.withoutFlavors();
    JavaLibrary baseLibrary = (JavaLibrary) graphBuilder.requireRule(unflavored);

    ImmutableSortedSet<SourcePath> sources = baseLibrary.getJavaSrcs();

    // Add only those dependencies that contribute to "direct" classpath of base library.
    // This includes direct deps and exported deps of direct deps.
    ImmutableSortedSet.Builder<SourcePath> directClasspathBuilder =
        ImmutableSortedSet.naturalOrder();
    baseLibrary
        .getDepsForTransitiveClasspathEntries()
        .forEach(
            dep -> {
              if (dep instanceof JavaLibrary) {
                directClasspathBuilder.addAll(
                    JavaLibraryClasspathProvider.getOutputClasspathJars(
                        (JavaLibrary) dep, Optional.ofNullable(dep.getSourcePathToOutput())));
              }
            });
    // Add exported deps of base lib into classpath (since 2nd argument is empty the result doesn't
    // include the lib itself).
    directClasspathBuilder.addAll(
        JavaLibraryClasspathProvider.getOutputClasspathJars(baseLibrary, Optional.empty()));
    ImmutableSortedSet<SourcePath> directClasspath = directClasspathBuilder.build();

    SourcePath outputJar = baseLibrary.getSourcePathToOutput();

    InferPlatform platform =
        unresolvedInferPlatform.resolve(graphBuilder, buildTarget.getTargetConfiguration());

    return new InferNullsafe(
        buildTarget,
        projectFilesystem,
        graphBuilder,
        new Impl(
            platform,
            config.getNullsafeArgs(),
            sources,
            directClasspath,
            javacOptions,
            extraClasspathProvider,
            outputJar,
            config.getPrettyPrint()));
  }

  @Override
  @Nullable
  public SourcePath getSourcePathToOutput() {
    if (getBuildable().producesOutput()) {
      return getSourcePath(getBuildable().reportJson);
    } else {
      return null;
    }
  }

  /** {@link Buildable} that is responsible for running Nullsafe. */
  static class Impl implements Buildable {
    private static final String INFER_DEFAULT_RESULT_DIR = "infer-out";

    // This is the default name of the JSON report which is suitable for consumption
    // by tools and automation.
    private static final String INFER_JSON_REPORT_FILE = "report.json";

    // Default name of the human-readable report is bugs.txt, renamed here for consistency.
    // We produce this report so that a user can see the full list of warnings later on.
    private static final String INFER_TXT_REPORT_FILE = "report.txt";

    private static final String REPORTS_OUTPUT_DIR = ".";

    // This flag instructs infer to run only nullsafe related checks. In practice, we may want to
    // add some other checks to run alongside nullsafe.
    private static final ImmutableList<String> NULLSAFE_DEFAULT_ARGS =
        ImmutableList.of("--eradicate-only");

    @AddToRuleKey private final ImmutableSortedSet<SourcePath> sources;
    @AddToRuleKey private final ImmutableSortedSet<SourcePath> classpath;

    @AddToRuleKey private final JavacOptions javacOptions;
    @AddToRuleKey private final Optional<ExtraClasspathProvider> extraClasspathProvider;

    @AddToRuleKey private final InferPlatform inferPlatform;
    @AddToRuleKey private final ImmutableList<String> nullsafeArgs;
    @AddToRuleKey @Nullable private final SourcePath generatedClasses;

    @AddToRuleKey private final OutputPath reportsDir = new OutputPath(REPORTS_OUTPUT_DIR);
    @AddToRuleKey private final OutputPath reportJson = reportsDir.resolve(INFER_JSON_REPORT_FILE);
    @AddToRuleKey private final OutputPath reportTxt = reportsDir.resolve(INFER_TXT_REPORT_FILE);

    // Whether to pretty print a list of issues to console and report.txt.
    // Pretty printing every time during build is distracting and incurs
    // ~10% overhead, so we don't want to do it, unless explicitly asked.
    @AddToRuleKey private final Boolean prettyPrint;

    Impl(
        InferPlatform inferPlatform,
        ImmutableList<String> nullsafeArgs,
        ImmutableSortedSet<SourcePath> sources,
        ImmutableSortedSet<SourcePath> classpath,
        JavacOptions javacOptions,
        Optional<ExtraClasspathProvider> extraClasspathProvider,
        @Nullable SourcePath generatedClasses,
        Boolean prettyPrint) {
      this.inferPlatform = inferPlatform;

      // Ensure sensible default behavior
      if (nullsafeArgs.isEmpty()) {
        this.nullsafeArgs = NULLSAFE_DEFAULT_ARGS;
      } else {
        this.nullsafeArgs = nullsafeArgs;
      }

      this.sources = sources;
      this.classpath = classpath;
      this.javacOptions = javacOptions;
      this.extraClasspathProvider = extraClasspathProvider;
      this.generatedClasses = generatedClasses;
      this.prettyPrint = prettyPrint;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      if (!producesOutput()) {
        return ImmutableList.of();
      }

      SourcePathResolverAdapter sourcePathResolverAdapter = buildContext.getSourcePathResolver();

      Path scratchDir = filesystem.resolve(outputPathResolver.getTempPath());
      Path argFilePath = filesystem.relativize(scratchDir.resolve("args.txt"));
      Path inferOutPath = filesystem.relativize(scratchDir.resolve(INFER_DEFAULT_RESULT_DIR));
      Path inferJsonReport = inferOutPath.resolve(INFER_JSON_REPORT_FILE);

      ImmutableList.Builder<Step> steps = ImmutableList.builder();

      // Prepare infer command line arguments and write them to args.txt
      ImmutableList<String> argsBuilder =
          buildArgs(inferOutPath, filesystem, sourcePathResolverAdapter, outputPathResolver);
      steps.add(
          new WriteFileStep(
              filesystem, Joiner.on(System.lineSeparator()).join(argsBuilder), argFilePath, false));

      // Prepare and invoke cmd with appropriate environment
      ImmutableList<String> cmd = buildCommand(argFilePath, sourcePathResolverAdapter);
      ImmutableMap<String, String> cmdEnv = buildEnv(sourcePathResolverAdapter);
      steps.add(
          new DefaultShellStep(filesystem.getRootPath(), cmd, cmdEnv) {
            @Override
            protected boolean shouldPrintStdout(Verbosity verbosity) {
              return verbosity.shouldPrintBinaryRunInformation();
            }

            @Override
            protected boolean shouldPrintStderr(Verbosity verbosity) {
              return verbosity.shouldPrintBinaryRunInformation();
            }
          });

      steps.add(
          CopyStep.forFile(
              filesystem, inferJsonReport, outputPathResolver.resolvePath(reportJson)));

      return steps.build();
    }

    public boolean producesOutput() {
      return generatedClasses != null && !sources.isEmpty();
    }

    private ImmutableMap<String, String> buildEnv(
        SourcePathResolverAdapter sourcePathResolverAdapter) {
      ImmutableMap.Builder<String, String> cmdEnv = ImmutableMap.builder();
      inferPlatform.getInferVersion().ifPresent(v -> cmdEnv.put("INFERVERSION", v));
      inferPlatform
          .getInferConfig()
          .map(sourcePathResolverAdapter::getAbsolutePath)
          .map(Objects::toString)
          .ifPresent(c -> cmdEnv.put("INFERCONFIG", c));

      return cmdEnv.build();
    }

    private ImmutableList<String> buildCommand(
        Path argFilePath, SourcePathResolverAdapter sourcePathResolverAdapter) {
      ImmutableList.Builder<String> cmd = ImmutableList.builder();
      cmd.addAll(inferPlatform.getInferBin().getCommandPrefix(sourcePathResolverAdapter));
      cmd.add("@" + argFilePath.toString());

      return cmd.build();
    }

    private ImmutableList<String> buildArgs(
        Path inferOutPath,
        ProjectFilesystem filesystem,
        SourcePathResolverAdapter sourcePathResolverAdapter,
        OutputPathResolver outputPathResolver) {
      ImmutableList.Builder<String> argsBuilder = ImmutableList.builder();
      argsBuilder.addAll(nullsafeArgs);
      argsBuilder.add(
          "--project-root",
          filesystem.getRootPath().toString(),
          "--jobs",
          "1",
          "--results-dir",
          inferOutPath.toString());

      if (prettyPrint) {
        argsBuilder.add("--issues-txt", outputPathResolver.resolvePath(reportTxt).toString());
      } else {
        argsBuilder.add("--report-hook-reset");
      }

      sources.stream()
          .map(s -> filesystem.relativize(sourcePathResolverAdapter.getAbsolutePath(s)))
          .map(Path::toString)
          .forEach(s -> argsBuilder.add("--sources", s));

      addNotEmpty(
          argsBuilder,
          "--classpath",
          classpath.stream()
              .map(s -> filesystem.relativize(sourcePathResolverAdapter.getAbsolutePath(s)))
              .map(Path::toString)
              .collect(Collectors.joining(File.pathSeparator)));

      JavacOptions buildTimeOptions =
          extraClasspathProvider
              .map(javacOptions::withBootclasspathFromContext)
              .orElse(javacOptions);

      Optional<String> bootClasspathOverride = buildTimeOptions.getBootclasspath();
      ImmutableList<SourcePath> bootClasspath = buildTimeOptions.getSourceLevelBootclasspath();

      addNotEmpty(
          argsBuilder,
          "--bootclasspath",
          bootClasspathOverride.orElseGet(
              () ->
                  bootClasspath.stream()
                      .map(s -> filesystem.relativize(sourcePathResolverAdapter.getAbsolutePath(s)))
                      .map(Path::toString)
                      .collect(Collectors.joining(File.pathSeparator))));

      argsBuilder.add(
          "--generated-classes",
          filesystem
              .relativize(sourcePathResolverAdapter.getAbsolutePath(generatedClasses))
              .toString());

      inferPlatform
          .getNullsafeThirdPartySignatures()
          .map(x -> sourcePathResolverAdapter.getAbsolutePath(x).toString())
          .ifPresent(dir -> addNotEmpty(argsBuilder, "--nullsafe-third-party-signatures", dir));

      return argsBuilder.build();
    }

    private static void addNotEmpty(
        ImmutableList.Builder<String> argBuilder, String argName, String argValue) {
      if (!argValue.isEmpty()) {
        argBuilder.add(argName, argValue);
      }
    }
  }
}
