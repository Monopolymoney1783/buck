/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android;

import com.facebook.buck.android.dex.D8Options;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.io.filesystem.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.step.fs.XzStep;
import com.facebook.buck.step.isolatedsteps.common.WriteFileIsolatedStep;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.concurrent.MoreFutures;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.facebook.buck.util.types.Unit;
import com.facebook.buck.util.zip.ZipCompressionLevel;
import com.facebook.buck.zip.RepackZipEntriesStep;
import com.facebook.buck.zip.ZipScrubberStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableMultimap.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * Optimized dx command runner which can invoke multiple dx commands in parallel and also avoid
 * doing unnecessary dx invocations in the first place.
 *
 * <p>This is most appropriately represented as a build rule itself (which depends on individual dex
 * rules) however this would require significant refactoring of AndroidBinaryRule that would be
 * disruptive to other initiatives in flight (namely, ApkBuilder). It is also debatable that it is
 * even the right course of action given that it would require dynamically modifying the DAG.
 */
public class SmartDexingStep implements Step {
  public static final String SHORT_NAME = "smart_dex";
  private static final String SECONDARY_SOLID_DEX_EXTENSION = ".dex.jar.xzs";

  public interface DexInputHashesProvider {
    ImmutableMap<Path, Sha1HashCode> getDexInputHashes();
  }

  private final AndroidPlatformTarget androidPlatformTarget;
  private final BuildContext buildContext;
  private final ProjectFilesystem filesystem;
  private final boolean desugarInterfaceMethods;
  private final Optional<Path> primaryDexClassNamesPath;
  private final Supplier<Multimap<Path, Path>> outputToInputsSupplier;
  private final Optional<Path> secondaryOutputDir;
  private final DexInputHashesProvider dexInputHashesProvider;
  private final Path successDir;
  private final EnumSet<D8Options> dxOptions;
  private final ListeningExecutorService executorService;
  private final int xzCompressionLevel;
  private final boolean useDexBuckedId;
  private final Optional<Set<Path>> additonalDesugarDeps;
  private final BuildTarget buildTarget;
  private final Optional<Integer> minSdkVersion;

  /**
   * @param primaryOutputPath Path for the primary dex artifact.
   * @param primaryInputsToDex Set of paths to include as inputs for the primary dex artifact.
   * @param secondaryOutputDir Directory path for the secondary dex artifacts, if there are any.
   *     Note that this directory will be pruned such that only those secondary outputs generated by
   *     this command will remain in the directory!
   * @param secondaryInputsToDex List of paths to input jar files, to use as dx input, keyed by the
   *     corresponding output dex file. Note that for each output file (key), a separate dx
   *     invocation will be started with the corresponding jar files (value) as the input.
   * @param successDir Directory where success artifacts are written.
   * @param executorService The thread pool to execute the dx command on.
   * @param minSdkVersion
   */
  public SmartDexingStep(
      AndroidPlatformTarget androidPlatformTarget,
      BuildContext buildContext,
      ProjectFilesystem filesystem,
      Optional<Path> primaryOutputPath,
      Optional<Supplier<Set<Path>>> primaryInputsToDex,
      Optional<Path> primaryDexClassNamesPath,
      Optional<Path> secondaryOutputDir,
      Optional<Supplier<Multimap<Path, Path>>> secondaryInputsToDex,
      DexInputHashesProvider dexInputHashesProvider,
      Path successDir,
      EnumSet<D8Options> dxOptions,
      ListeningExecutorService executorService,
      int xzCompressionLevel,
      boolean desugarInterfaceMethods,
      boolean useDexBuckedId,
      Optional<Set<Path>> additonalDesugarDeps,
      BuildTarget buildTarget,
      Optional<Integer> minSdkVersion) {
    this.androidPlatformTarget = androidPlatformTarget;
    this.buildContext = buildContext;
    this.filesystem = filesystem;
    this.desugarInterfaceMethods = desugarInterfaceMethods;
    this.outputToInputsSupplier =
        MoreSuppliers.memoize(
            () -> {
              Builder<Path, Path> map = ImmutableMultimap.builder();
              if (primaryInputsToDex.isPresent()) {
                map.putAll(primaryOutputPath.get(), primaryInputsToDex.get().get());
              }
              if (secondaryInputsToDex.isPresent()) {
                map.putAll(secondaryInputsToDex.get().get());
              }
              return map.build();
            });
    this.primaryDexClassNamesPath = primaryDexClassNamesPath;
    this.secondaryOutputDir = secondaryOutputDir;
    this.dexInputHashesProvider = dexInputHashesProvider;
    this.successDir = successDir;
    this.dxOptions = dxOptions;
    this.executorService = executorService;
    this.xzCompressionLevel = xzCompressionLevel;
    this.useDexBuckedId = useDexBuckedId;
    this.additonalDesugarDeps = additonalDesugarDeps;
    this.buildTarget = buildTarget;
    this.minSdkVersion = minSdkVersion;
  }

  /**
   * @return Optimal (in terms of both memory and performance) number of parallel threads to run
   *     dexer. The implementation uses running machine hardware characteristics to determine this.
   */
  public static int determineOptimalThreadCount() {
    // Most processors these days have hyperthreading that multiplies the amount of logical
    // processors reported by Java. So in case of 1 CPU, 2 physical cores with hyperthreading, the
    // call to Runtime.getRuntime().availableProcessors() would return 1*2*2 = 4, assuming 2 hyper
    // threads per core, which is common but in fact may be more than that.
    // Using hyper threads does not help to dex faster, but consumes a lot of memory, so it makes
    // sense to base heuristics on the number of physical cores.
    // Unfortunately there is no good way to detect the number of physical cores in pure Java,
    // so we just divide the total number of logical processors by two to cover the majority of
    // cases.
    // TODO(buck_team): Implement cross-platform hardware capabilities detection and use it here
    return Math.max(Runtime.getRuntime().availableProcessors() / 2, 1);
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context)
      throws IOException, InterruptedException {

    Multimap<Path, Path> outputToInputs;
    try {
      outputToInputs = outputToInputsSupplier.get();
      runDxCommands(context, outputToInputs);
    } catch (StepFailedException e) {
      Optional<DexOverflowError.OverflowType> overflowType = DexOverflowError.checkOverflow(e);
      if (overflowType.isPresent()) {
        DexOverflowError error =
            new DexOverflowError(filesystem, overflowType.get(), (D8Step) e.getStep());
        context.getConsole().printErrorText(error.getErrorMessage());
      } else {
        context.logError(e, "There was an error in smart dexing step.");
      }
      return StepExecutionResults.ERROR;
    }

    if (outputToInputs != null && secondaryOutputDir.isPresent()) {
      removeExtraneousSecondaryArtifacts(
          secondaryOutputDir.get(), outputToInputs.keySet(), filesystem);

      ImmutableMultimap<Path, Path> xzsOutputsToInputs =
          createXzsOutputsToInputs(outputToInputs.keySet());
      if (!xzsOutputsToInputs.isEmpty()) {
        try {
          runXzsCommands(
              context,
              xzsOutputsToInputs,
              filesystem,
              xzCompressionLevel,
              Optional.of(buildTarget));
        } catch (StepFailedException e) {
          context.logError(e, "There was an error producing an xzs file from dex jars");
          return StepExecutionResults.ERROR;
        }
      }
    }

    return StepExecutionResults.SUCCESS;
  }

  static ImmutableMultimap<Path, Path> createXzsOutputsToInputs(Collection<Path> outputs) {
    // Concatenate if solid compression is specified.
    // create a mapping of the xzs file target and the dex.jar files that go into it
    ImmutableMultimap.Builder<Path, Path> xzsMultimapBuilder = ImmutableMultimap.builder();
    for (Path p : outputs) {
      if (DexStore.XZS.matchesPath(p)) {
        String[] matches = p.getFileName().toString().split("-");
        Path output = p.getParent().resolve(matches[0].concat(SECONDARY_SOLID_DEX_EXTENSION));
        xzsMultimapBuilder.put(output, p);
      }
    }
    return xzsMultimapBuilder.build();
  }

  static void runXzsCommands(
      StepExecutionContext context,
      ImmutableMultimap<Path, Path> outputsToInputs,
      ProjectFilesystem filesystem,
      int xzCompressionLevel,
      Optional<BuildTarget> buildTarget)
      throws StepFailedException, InterruptedException {
    for (Map.Entry<Path, Collection<Path>> entry : outputsToInputs.asMap().entrySet()) {
      Path secondaryCompressedBlobOutput = entry.getKey();
      Collection<Path> secondaryDexJars = entry.getValue();
      // Construct the output path for our solid blob and its compressed form.
      Path secondaryBlobOutput =
          secondaryCompressedBlobOutput.getParent().resolve("uncompressed.dex.blob");
      // Concatenate the jars into a blob and compress it.
      Step concatStep =
          new ConcatStep(filesystem, ImmutableList.copyOf(secondaryDexJars), secondaryBlobOutput);
      Step xzStep =
          new XzStep(
              filesystem, secondaryBlobOutput, secondaryCompressedBlobOutput, xzCompressionLevel);

      StepRunner.runStep(context, concatStep, buildTarget);
      StepRunner.runStep(context, xzStep, buildTarget);
    }
  }

  private void runDxCommands(StepExecutionContext context, Multimap<Path, Path> outputToInputs)
      throws StepFailedException, InterruptedException {
    // Invoke dx commands in parallel for maximum thread utilization.  In testing, dx revealed
    // itself to be CPU (and not I/O) bound making it a good candidate for parallelization.
    Stream<ImmutableList<Step>> dxSteps = generateDxCommands(filesystem, outputToInputs);

    ImmutableList<Callable<Unit>> callables =
        dxSteps
            .map(
                steps ->
                    (Callable<Unit>)
                        () -> {
                          for (Step step : steps) {
                            StepRunner.runStep(context, step, Optional.of(buildTarget));
                          }
                          return Unit.UNIT;
                        })
            .collect(ImmutableList.toImmutableList());

    try {
      MoreFutures.getAll(executorService, callables);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      Throwables.throwIfInstanceOf(cause, StepFailedException.class);

      // Programmer error.  Boo-urns.
      throw new RuntimeException(cause);
    }
  }

  /**
   * Prune the secondary output directory of any files that we didn't generate. This is needed
   * because we crudely add all files in this directory to the final APK, but the number may have
   * been reduced due to split-zip having less code to process.
   *
   * <p>This is also a defensive measure to cleanup extraneous artifacts left behind due to changes
   * to buck itself.
   */
  private void removeExtraneousSecondaryArtifacts(
      Path secondaryOutputDir, Set<Path> producedArtifacts, ProjectFilesystem projectFilesystem)
      throws IOException {
    secondaryOutputDir = secondaryOutputDir.normalize();
    for (Path secondaryOutput : projectFilesystem.getDirectoryContents(secondaryOutputDir)) {
      if (!producedArtifacts.contains(secondaryOutput)
          && !secondaryOutput.getFileName().toString().endsWith(".meta")) {
        projectFilesystem.deleteRecursivelyIfExists(secondaryOutput);
      }
    }
  }

  @Override
  public String getShortName() {
    return SHORT_NAME;
  }

  @Override
  public String getDescription(StepExecutionContext context) {
    StringBuilder b = new StringBuilder();
    b.append(getShortName());
    minSdkVersion.ifPresent(minSdk -> b.append("--min-sdk-version ").append(minSdk));
    Multimap<Path, Path> outputToInputs = outputToInputsSupplier.get();
    for (Path output : outputToInputs.keySet()) {
      b.append(" -out ");
      b.append(output);
      b.append(" -in ");
      Joiner.on(':').appendTo(b, Iterables.transform(outputToInputs.get(output), Object::toString));
    }

    return b.toString();
  }

  /**
   * Once the {@code .class} files have been split into separate zip files, each must be converted
   * to a {@code .dex} file.
   */
  private Stream<ImmutableList<Step>> generateDxCommands(
      ProjectFilesystem filesystem, Multimap<Path, Path> outputToInputs) {

    ImmutableMap<Path, Sha1HashCode> dexInputHashes = dexInputHashesProvider.getDexInputHashes();
    ImmutableSet<Path> allDexInputPaths = ImmutableSet.copyOf(outputToInputs.values());

    return outputToInputs.asMap().entrySet().stream()
        .map(
            outputInputsPair ->
                new DxPseudoRule(
                    androidPlatformTarget,
                    buildContext,
                    filesystem,
                    dexInputHashes,
                    ImmutableSet.copyOf(outputInputsPair.getValue()),
                    outputInputsPair.getKey(),
                    successDir.resolve(outputInputsPair.getKey().getFileName()),
                    dxOptions,
                    primaryDexClassNamesPath,
                    xzCompressionLevel,
                    desugarInterfaceMethods
                        ? Sets.union(
                            Sets.difference(
                                allDexInputPaths, ImmutableSet.copyOf(outputInputsPair.getValue())),
                            additonalDesugarDeps.orElse(ImmutableSet.of()))
                        : null,
                    useDexBuckedId,
                    minSdkVersion))
        .filter(dxPseudoRule -> !dxPseudoRule.checkIsCached())
        .map(
            dxPseudoRule -> {
              ImmutableList.Builder<Step> steps = ImmutableList.builder();
              dxPseudoRule.buildInternal(steps);
              return steps.build();
            });
  }

  /**
   * Internally designed to simulate a dexing buck rule so that once refactored more broadly as such
   * it should be straightforward to convert this code.
   *
   * <p>This pseudo rule does not use the normal .success file model but instead checksums its
   * inputs. This is because the input zip files are guaranteed to have changed on the filesystem
   * (ZipSplitter will always write them out even if the same), but the contents contained in the
   * zip may not have changed.
   */
  @VisibleForTesting
  static class DxPseudoRule {

    private final AndroidPlatformTarget androidPlatformTarget;
    private final BuildContext buildContext;
    private final ProjectFilesystem filesystem;
    private final Map<Path, Sha1HashCode> dexInputHashes;
    private final Set<Path> srcs;
    private final Path outputPath;
    private final Path outputHashPath;
    private final EnumSet<D8Options> dxOptions;
    private final Optional<Path> primaryDexClassNamesPath;
    @Nullable private String newInputsHash;
    private final int xzCompressionLevel;
    @Nullable private final Collection<Path> classpathFiles;
    private final boolean useDexBuckedId;
    private final Optional<Integer> minSdkVersion;

    public DxPseudoRule(
        AndroidPlatformTarget androidPlatformTarget,
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        Map<Path, Sha1HashCode> dexInputHashes,
        Set<Path> srcs,
        Path outputPath,
        Path outputHashPath,
        EnumSet<D8Options> dxOptions,
        Optional<Path> primaryDexClassNamesPath,
        int xzCompressionLevel,
        @Nullable Collection<Path> classpathFiles,
        boolean useDexBuckedId,
        Optional<Integer> minSdkVersion) {
      this.androidPlatformTarget = androidPlatformTarget;
      this.buildContext = buildContext;
      this.filesystem = filesystem;
      this.dexInputHashes = ImmutableMap.copyOf(dexInputHashes);
      this.srcs = ImmutableSet.copyOf(srcs);
      this.outputPath = outputPath;
      this.outputHashPath = outputHashPath;
      this.dxOptions = dxOptions;
      this.primaryDexClassNamesPath = primaryDexClassNamesPath;
      this.xzCompressionLevel = xzCompressionLevel;
      this.classpathFiles = classpathFiles;
      this.useDexBuckedId = useDexBuckedId;
      this.minSdkVersion = minSdkVersion;
    }

    /**
     * Read the previous run's hash from the filesystem.
     *
     * @return Previous hash if there was one; null otherwise.
     */
    @Nullable
    private String getPreviousInputsHash() {
      // Returning null will trigger the dx command to run again.
      return filesystem.readFirstLine(outputHashPath).orElse(null);
    }

    @VisibleForTesting
    String hashInputs() {
      Hasher hasher = Hashing.sha1().newHasher();
      for (Path src : srcs) {
        Preconditions.checkState(
            dexInputHashes.containsKey(src), "no hash key exists for path %s", src.toString());
        Sha1HashCode hash = Objects.requireNonNull(dexInputHashes.get(src));
        hash.update(hasher);
      }

      primaryDexClassNamesPath.ifPresent(
          path -> {
            try {
              filesystem.computeSha1(path).update(hasher);
            } catch (IOException e) {
              throw new RuntimeException("Cannot calculate Sha1 for: " + path);
            }
          });

      return hasher.hash().toString();
    }

    public boolean checkIsCached() {
      newInputsHash = hashInputs();

      if (!filesystem.exists(outputHashPath) || !filesystem.exists(outputPath)) {
        return false;
      }

      // Verify input hashes.
      String currentInputsHash = getPreviousInputsHash();
      return newInputsHash.equals(currentInputsHash);
    }

    private void buildInternal(ImmutableList.Builder<Step> steps) {
      Preconditions.checkState(newInputsHash != null, "Must call checkIsCached first!");

      createDxStepForDxPseudoRule(
          androidPlatformTarget,
          steps,
          buildContext,
          filesystem,
          srcs,
          outputPath,
          dxOptions,
          primaryDexClassNamesPath,
          xzCompressionLevel,
          classpathFiles,
          useDexBuckedId,
          minSdkVersion);
      steps.add(WriteFileIsolatedStep.of(newInputsHash, outputHashPath, /* executable */ false));
    }
  }

  /**
   * The step to produce the .dex file will be determined by the file extension of outputPath, much
   * as {@code dx} itself chooses whether to embed the dex inside a jar/zip based on the destination
   * file passed to it. We also create a ".meta" file that contains information about the compressed
   * and uncompressed size of the dex; this information is useful later, in applications, when
   * unpacking.
   */
  static void createDxStepForDxPseudoRule(
      AndroidPlatformTarget androidPlatformTarget,
      ImmutableList.Builder<Step> steps,
      BuildContext context,
      ProjectFilesystem filesystem,
      Collection<Path> filesToDex,
      Path outputPath,
      EnumSet<D8Options> dxOptions,
      Optional<Path> primaryDexClassNamesPath,
      int xzCompressionLevel,
      @Nullable Collection<Path> classpathFiles,
      boolean useDexBuckedId,
      Optional<Integer> minSdkVersion) {

    Optional<String> buckedId = Optional.empty();
    String output = outputPath.toString();
    String fileName = Files.getNameWithoutExtension(output);
    if (useDexBuckedId && fileName.startsWith("classes")) {
      // We know what the output file name is ("classes.dex" or "classesN.dex") as these
      // are generated in SplitZipStep and passed around as part of a multi-map - it is
      // simply easier and cleaner to extract the dex file number to be used as unique
      // identifier rather than creating another map and pass it around
      String[] tokens = fileName.split("classes");
      String id = tokens.length == 0 ? "" /* primary */ : tokens[1] /* secondary */;
      buckedId = Optional.of(id);
    }

    FileSystem fileSystem = filesystem.getFileSystem();
    if (DexStore.XZ.matchesPath(outputPath)) {
      Path tempDexJarOutput = fileSystem.getPath(output.replaceAll("\\.jar\\.xz$", ".tmp.jar"));
      steps.add(
          new D8Step(
              filesystem,
              androidPlatformTarget,
              tempDexJarOutput,
              filesToDex,
              dxOptions,
              primaryDexClassNamesPath,
              classpathFiles,
              buckedId,
              minSdkVersion));
      // We need to make sure classes.dex is STOREd in the .dex.jar file, otherwise .XZ
      // compression won't be effective.
      Path repackedJar = fileSystem.getPath(output.replaceAll("\\.xz$", ""));
      steps.add(
          new RepackZipEntriesStep(
              filesystem,
              tempDexJarOutput,
              repackedJar,
              ImmutableSet.of("classes.dex"),
              ZipCompressionLevel.NONE));
      steps.add(
          RmStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(), filesystem, tempDexJarOutput)));
      steps.add(
          new DexJarAnalysisStep(
              filesystem,
              repackedJar,
              repackedJar.resolveSibling(repackedJar.getFileName() + ".meta")));

      steps.add(new XzStep(filesystem, repackedJar, xzCompressionLevel));
    } else if (DexStore.XZS.matchesPath(outputPath)) {
      // Essentially the same logic as the XZ case above, except we compress later.
      // The differences in output file names make it worth separating into a different case.

      // Ensure classes.dex is stored.
      Path tempDexJarOutput =
          fileSystem.getPath(output.replaceAll("\\.jar\\.xzs\\.tmp~$", ".tmp.jar"));
      steps.add(
          new D8Step(
              filesystem,
              androidPlatformTarget,
              tempDexJarOutput,
              filesToDex,
              dxOptions,
              primaryDexClassNamesPath,
              classpathFiles,
              buckedId,
              minSdkVersion));
      steps.add(
          new RepackZipEntriesStep(
              filesystem,
              tempDexJarOutput,
              outputPath,
              ImmutableSet.of("classes.dex"),
              ZipCompressionLevel.NONE));
      steps.add(
          RmStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(), filesystem, tempDexJarOutput)));

      // Write a .meta file.
      steps.add(
          new DexJarAnalysisStep(
              filesystem,
              outputPath,
              outputPath.resolveSibling(outputPath.getFileName() + ".meta")));
    } else if (DexStore.JAR.matchesPath(outputPath)
        || DexStore.RAW.matchesPath(outputPath)
        || output.endsWith("classes.dex")
        || primaryDexClassNamesPath.isPresent()) {
      steps.add(
          new D8Step(
              filesystem,
              androidPlatformTarget,
              outputPath,
              filesToDex,
              dxOptions,
              primaryDexClassNamesPath,
              classpathFiles,
              buckedId,
              minSdkVersion));
      if (DexStore.JAR.matchesPath(outputPath)) {
        steps.add(
            new DexJarAnalysisStep(
                filesystem,
                outputPath,
                outputPath.resolveSibling(outputPath.getFileName() + ".meta")));
        steps.add(ZipScrubberStep.of(filesystem.resolve(outputPath)));
      }
    } else {
      throw new IllegalArgumentException(
          String.format("Suffix of %s does not have a corresponding DexStore type.", outputPath));
    }
  }
}
