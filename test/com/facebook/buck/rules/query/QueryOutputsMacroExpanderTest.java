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

package com.facebook.buck.rules.query;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.ConstantHostTargetConfigurationResolver;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.FakeTargetNodeArg;
import com.facebook.buck.core.model.targetgraph.FakeTargetNodeBuilder;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.NoopBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.QueryOutputsMacroExpander;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.testutil.HashMapWithStats;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import java.nio.file.Paths;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Tests for the query macro. See {@link com.facebook.buck.shell.GenruleDescriptionIntegrationTest}
 * for some less contrived integration tests.
 */
public class QueryOutputsMacroExpanderTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectFilesystem filesystem;
  private ActionGraphBuilder graphBuilder;
  private CellNameResolver cellNameResolver;
  private BuildRule rule;
  private BuildRule dep;
  private HashMapWithStats<Macro, Object> cache;
  private BuildRule noopRule;
  private StringWithMacrosConverter converter;

  @Before
  public void setUp() {
    cache = new HashMapWithStats<>();
    filesystem = new FakeProjectFilesystem(CanonicalCellName.rootCell(), tmp.getRoot());
    cellNameResolver = TestCellBuilder.createCellRoots(filesystem).getCellNameResolver();
    TargetNode<?> depNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//exciting:dep"), filesystem)
            .addSrc(Paths.get("Dep.java"))
            .build();

    TargetNode<?> ruleNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//exciting:target"), filesystem)
            .addSrc(Paths.get("Other.java"))
            .addDep(depNode.getBuildTarget())
            .build();

    TargetNode<?> noopNode1 = newNoopNode("//fake:no-op-1");
    TargetNode<?> noopNode2 = newNoopNode("//fake:no-op-2");

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(depNode, ruleNode, noopNode2, noopNode1);

    graphBuilder = new TestActionGraphBuilder(targetGraph, filesystem);

    dep = graphBuilder.requireRule(depNode.getBuildTarget());
    rule = graphBuilder.requireRule(ruleNode.getBuildTarget());
    noopRule = graphBuilder.requireRule(noopNode1.getBuildTarget());
    graphBuilder.requireRule(noopNode2.getBuildTarget());

    converter =
        StringWithMacrosConverter.of(
            ruleNode.getBuildTarget(),
            cellNameResolver,
            graphBuilder,
            ImmutableList.of(new QueryOutputsMacroExpander(TargetGraph.EMPTY)),
            Optional.empty(),
            cache);
  }

  @Test
  public void classpathFunction() throws Exception {
    assertExpandsTo(
        "$(query_outputs 'classpath(//exciting:target)')",
        rule,
        String.format(
            "%s %s",
            absolutify(
                BuildTargetFactory.newInstance("//exciting:dep"), "lib__%s__output", "dep.jar"),
            absolutify(
                BuildTargetFactory.newInstance("//exciting:target"),
                "lib__%s__output",
                "target.jar")));
  }

  @Test
  public void noOutputs() throws Exception {
    assertExpandsTo("$(query_outputs 'set(//fake:no-op-1 //fake:no-op-2)')", noopRule, "");
  }

  @Test
  public void literals() throws Exception {
    assertExpandsTo(
        "$(query_outputs 'set(//exciting:target //exciting:dep)')",
        rule,
        String.format(
            "%s %s",
            absolutify(
                BuildTargetFactory.newInstance("//exciting:dep"), "lib__%s__output", "dep.jar"),
            absolutify(
                BuildTargetFactory.newInstance("//exciting:target"),
                "lib__%s__output",
                "target.jar")));
  }

  @Test
  public void canUseCacheOfPrecomputedWork() throws Exception {
    coerceAndStringify("$(query_outputs 'classpath(//exciting:target)')", dep);
    // Cache should be populated at this point
    assertThat(cache.values(), Matchers.hasSize(1));
    assertEquals(1, cache.numPuts());

    int getsSoFar = cache.numGets();
    assertExpandsTo(
        "$(query_outputs 'classpath(//exciting:target)')",
        rule,
        String.format(
            "%s %s",
            absolutify(
                BuildTargetFactory.newInstance("//exciting:dep"), "lib__%s__output", "dep.jar"),
            absolutify(
                BuildTargetFactory.newInstance("//exciting:target"),
                "lib__%s__output",
                "target.jar")));
    // No new cache entry should have appeared
    assertThat(cache.values(), Matchers.hasSize(1));
    assertEquals(1, cache.numPuts());
    // And we should have been able to read the value
    assertEquals(getsSoFar + 1, cache.numGets());
  }

  private void assertExpandsTo(String input, BuildRule rule, String expected) throws Exception {
    String results = coerceAndStringify(input, rule);
    assertEquals(expected, results);
  }

  private String absolutify(BuildTarget buildTarget, String format, String fileName) {
    return filesystem
        .resolve(BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), buildTarget, format))
        .resolve(fileName)
        .toString();
  }

  private TargetNode<FakeTargetNodeArg> newNoopNode(String buildTarget) {
    return FakeTargetNodeBuilder.build(
        new NoopBuildRule(BuildTargetFactory.newInstance(buildTarget), filesystem));
  }

  private String coerceAndStringify(String input, BuildRule rule) throws CoerceFailedException {
    StringWithMacros stringWithMacros =
        new DefaultTypeCoercerFactory()
            .typeCoercerForType(TypeToken.of(StringWithMacros.class))
            .coerceBoth(
                cellNameResolver,
                filesystem,
                rule.getBuildTarget().getCellRelativeBasePath().getPath(),
                UnconfiguredTargetConfiguration.INSTANCE,
                new ConstantHostTargetConfigurationResolver(
                    UnconfiguredTargetConfiguration.INSTANCE),
                input);
    Arg arg = converter.convert(stringWithMacros);
    return Arg.stringify(arg, graphBuilder.getSourcePathResolver());
  }
}
