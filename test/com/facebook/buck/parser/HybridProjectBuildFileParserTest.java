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

package com.facebook.buck.parser;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.Syntax;
import com.facebook.buck.parser.config.DefaultBuildFileSyntaxMapping;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.skylark.parser.SkylarkProjectBuildFileParser;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.collect.TwoArraysImmutableHashMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Files;
import org.easymock.EasyMock;
import org.easymock.EasyMockRunner;
import org.easymock.Mock;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

@RunWith(EasyMockRunner.class)
public class HybridProjectBuildFileParserTest {

  private static final BuildFileManifest EMPTY_BUILD_FILE_MANIFEST =
      BuildFileManifest.of(
          TwoArraysImmutableHashMap.of(),
          ImmutableSortedSet.of(),
          ImmutableMap.of(),
          ImmutableList.of(),
          ImmutableList.of());

  @Mock PythonDslProjectBuildFileParser pythonDslParser;
  @Mock SkylarkProjectBuildFileParser skylarkParser;

  private HybridProjectBuildFileParser parser;

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  private AbsPath buildFile;
  private ForwardRelPath buildFilePath;

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Before
  public void setUp() throws Exception {
    parser =
        HybridProjectBuildFileParser.using(
            ImmutableMap.of(Syntax.PYTHON_DSL, pythonDslParser, Syntax.SKYLARK, skylarkParser),
            DefaultBuildFileSyntaxMapping.ofOnly(Syntax.PYTHON_DSL),
            tmp.getRoot());
    buildFile = tmp.newFile("BUCK");
    buildFilePath = ForwardRelPath.of("BUCK");
  }

  @Test
  public void getAllRulesCallsPythonDslParserWhenRequestedExplicitly() throws Exception {
    EasyMock.expect(pythonDslParser.getManifest(buildFilePath))
        .andReturn(EMPTY_BUILD_FILE_MANIFEST);
    EasyMock.replay(pythonDslParser);
    Files.write(buildFile.getPath(), getParserDirectiveFor(Syntax.PYTHON_DSL).getBytes());
    parser.getManifest(buildFilePath);
    EasyMock.verify(pythonDslParser);
  }

  @Test
  public void getAllRulesCallsPythonDslParserByDefault() throws Exception {
    EasyMock.expect(pythonDslParser.getManifest(buildFilePath))
        .andReturn(EMPTY_BUILD_FILE_MANIFEST);
    EasyMock.replay(pythonDslParser);
    parser.getManifest(buildFilePath);
    EasyMock.verify(pythonDslParser);
  }

  @Test
  public void getAllRulesCallsSkylarkParserByWhenItIsRequestedExplicitly() throws Exception {
    EasyMock.expect(skylarkParser.getManifest(buildFilePath)).andReturn(EMPTY_BUILD_FILE_MANIFEST);
    EasyMock.replay(skylarkParser);
    Files.write(buildFile.getPath(), getParserDirectiveFor(Syntax.SKYLARK).getBytes());
    parser.getManifest(buildFilePath);
    EasyMock.verify(skylarkParser);
  }

  @Test
  public void getAllRulesCallsFailsOnInvalidSyntaxName() throws Exception {
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage(
        "Unrecognized syntax [SKILARK] requested for build file [" + buildFile + "]");
    Files.write(buildFile.getPath(), "# BUILD FILE SYNTAX: SKILARK".getBytes());
    parser.getManifest(buildFilePath);
  }

  @Test
  public void getBuildFileManifestCallsFailsOnUnsupportedSyntax() throws Exception {
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage("Syntax [PYTHON_DSL] is not supported for build file [" + buildFile + "]");
    parser =
        HybridProjectBuildFileParser.using(
            ImmutableMap.of(Syntax.SKYLARK, skylarkParser),
            DefaultBuildFileSyntaxMapping.ofOnly(Syntax.SKYLARK),
            tmp.getRoot());
    Files.write(buildFile.getPath(), "# BUILD FILE SYNTAX: PYTHON_DSL".getBytes());
    parser.getManifest(buildFilePath);
  }

  @Test
  public void reportProfileIsCalledForBothParsers() throws Exception {
    pythonDslParser.reportProfile();
    EasyMock.expectLastCall();
    skylarkParser.reportProfile();
    EasyMock.expectLastCall();
    EasyMock.replay(pythonDslParser, skylarkParser);
    parser.reportProfile();
    EasyMock.verify(pythonDslParser, skylarkParser);
  }

  @Test
  public void closeIsCalledForBothParsers() throws Exception {
    pythonDslParser.close();
    EasyMock.expectLastCall();
    skylarkParser.close();
    EasyMock.expectLastCall();
    EasyMock.replay(pythonDslParser, skylarkParser);
    parser.close();
    EasyMock.verify(pythonDslParser, skylarkParser);
  }

  @Test
  public void canInferSyntaxByName() {
    assertThat(Syntax.from("SKYLARK").get(), Matchers.is(Syntax.SKYLARK));
    assertThat(Syntax.from("PYTHON_DSL").get(), Matchers.is(Syntax.PYTHON_DSL));
  }

  @Test
  public void invalidSyntaxIsRecognized() {
    assertFalse(Syntax.from("INVALID_SYNTAX").isPresent());
  }

  /**
   * @return The buck parser directive, which should be added as the first line of the build file in
   *     order to request current syntax.
   */
  private String getParserDirectiveFor(Syntax syntax) {
    return HybridProjectBuildFileParser.SYNTAX_MARKER_START + syntax.name();
  }
}
