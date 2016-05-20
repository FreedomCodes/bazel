// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.analysis.select;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.syntax.Type.STRING;
import static org.junit.Assert.assertNull;

import com.google.common.base.Joiner;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.AggregatingAttributeMapper;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.rules.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.testutil.UnknownRuleConfiguredTarget;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link AggregatingAttributeMapper}.
 */
@RunWith(JUnit4.class)
public class AggregatingAttributeMapperTest extends AbstractAttributeMapperTest {

  @Before
  public final void createMapper() throws Exception {
    // Run AbstractAttributeMapper tests through an AggregatingAttributeMapper.
    mapper = AggregatingAttributeMapper.of(rule);
  }

  private static Label getDefaultMallocLabel(Rule rule) {
    return Verify.verifyNotNull(
        (Label) rule.getRuleClassObject().getAttributeByName("malloc").getDefaultValueForTesting());
  }

  /**
   * Tests that {@link AggregatingAttributeMapper#visitAttribute} returns an
   * attribute's sole value when declared directly (i.e. not as a configurable dict).
   */
  @Test
  public void testGetPossibleValuesDirectAttribute() throws Exception {
    Rule rule = scratchRule("a", "myrule",
        "sh_binary(name = 'myrule',",
        "          srcs = ['a.sh'])");
    assertThat(AggregatingAttributeMapper.of(rule).visitAttribute("srcs", BuildType.LABEL_LIST))
        .containsExactlyElementsIn(ImmutableList.of(ImmutableList.of(Label.create("@//a", "a.sh"))));
  }

  /**
   * Tests that {@link AggregatingAttributeMapper#visitAttribute} returns
   * every possible value that a configurable attribute can resolve to.
   */
  @Test
  public void testGetPossibleValuesConfigurableAttribute() throws Exception {
    Rule rule = scratchRule("a", "myrule",
        "sh_binary(name = 'myrule',",
        "          srcs = select({",
        "              '//conditions:a': ['a.sh'],",
        "              '//conditions:b': ['b.sh'],",
        "              '" + BuildType.Selector.DEFAULT_CONDITION_KEY + "': ['default.sh'],",
        "          }))");
    assertThat(AggregatingAttributeMapper.of(rule).visitAttribute("srcs", BuildType.LABEL_LIST))
        .containsExactlyElementsIn(
            ImmutableList.of(
                ImmutableList.of(Label.create("@//a", "a.sh")),
                ImmutableList.of(Label.create("@//a", "b.sh")),
                ImmutableList.of(Label.create("@//a", "default.sh"))));
  }

  @Test
  public void testGetPossibleValuesWithConcatenatedSelects() throws Exception {
    Rule rule = scratchRule("a", "myrule",
        "sh_binary(name = 'myrule',",
        "          srcs = select({",
        "                  '//conditions:a1': ['a1.sh'],",
        "                  '//conditions:b1': ['b1.sh']})",
        "              + select({",
        "                  '//conditions:a2': ['a2.sh'],",
        "                  '//conditions:b2': ['b2.sh']})",
        "          )");
    assertThat(AggregatingAttributeMapper.of(rule).visitAttribute("srcs", BuildType.LABEL_LIST))
        .containsExactlyElementsIn(
            ImmutableList.of(
                ImmutableList.of(Label.create("@//a", "a1.sh"), Label.create("@//a", "a2.sh")),
                ImmutableList.of(Label.create("@//a", "a1.sh"), Label.create("@//a", "b2.sh")),
                ImmutableList.of(Label.create("@//a", "b1.sh"), Label.create("@//a", "a2.sh")),
                ImmutableList.of(Label.create("@//a", "b1.sh"), Label.create("@//a", "b2.sh"))));
  }

  /**
   * Given a large number of selects, we expect better than the naive
   * exponential performance from evaluating select1 x select2 x select3 x ...
   */
  @Test
  public void testGetPossibleValuesWithManySelects() throws Exception {
    String pattern = " + select({'//conditions:a1': '%c', '//conditions:a2': '%s'})";
    StringBuilder ruleDef = new StringBuilder();
    ruleDef.append("genrule(name = 'gen', srcs = [], outs = ['gen.out'], cmd = ''");
    for (char c : "abcdefghijklmnopqrstuvwxyz".toCharArray()) {
      ruleDef.append(String.format(pattern, c, Character.toUpperCase(c)));
    }
    ruleDef.append(")");
    Rule rule = scratchRule("a", "gen", ruleDef.toString());
    // Naive evaluation would visit 2^26 cases and either overflow memory or timeout the test.
    assertThat(AggregatingAttributeMapper.of(rule).visitAttribute("cmd", Type.STRING))
        .containsExactly("abcdefghijklmnopqrstuvwxyz", "ABCDEFGHIJKLMNOPQRSTUVWXYZ");
  }

  /**
   * Tests that, on rule visitation, {@link AggregatingAttributeMapper} visits *every* possible
   * value in a configurable attribute (including configuration key labels).
   */
  @Test
  public void testVisitationConfigurableAttribute() throws Exception {
    Rule rule = scratchRule("a", "myrule",
        "sh_binary(name = 'myrule',",
        "          srcs = select({",
        "              '//conditions:a': ['a.sh'],",
        "              '//conditions:b': ['b.sh'],",
        "              '" + BuildType.Selector.DEFAULT_CONDITION_KEY + "': ['default.sh'],",
        "          }))");

    VisitationRecorder recorder = new VisitationRecorder("srcs");
    AggregatingAttributeMapper.of(rule).visitLabels(recorder);
    assertThat(recorder.labelsVisited)
        .containsExactlyElementsIn(
            ImmutableList.of(
                "//a:a.sh", "//a:b.sh", "//a:default.sh", "//conditions:a", "//conditions:b"));
  }

  @Test
  public void testVisitationWithDefaultValues() throws Exception {
    Rule rule = scratchRule("a", "myrule",
        "cc_binary(name = 'myrule',",
        "    srcs = [],",
        "    malloc = select({",
        "        '//conditions:a': None,",
        "    }))");

    VisitationRecorder recorder = new VisitationRecorder("malloc");
    AggregatingAttributeMapper.of(rule).visitLabels(recorder);
    assertThat(recorder.labelsVisited)
        .containsExactlyElementsIn(
            ImmutableList.of("//conditions:a", getDefaultMallocLabel(rule).toString()));
  }


  @Test
  public void testGetReachableLabels() throws Exception {
    Rule rule = scratchRule("x", "main",
        "cc_binary(",
        "    name = 'main',",
        "    srcs = select({",
        "        '//conditions:a': ['a.cc'],",
        "        '//conditions:b': ['b.cc']})",
        "    + ",
        "        ['always.cc']",
        "    + ",
        "         select({",
        "        '//conditions:c': ['c.cc'],",
        "        '//conditions:d': ['d.cc'],",
        "        '" + BuildType.Selector.DEFAULT_CONDITION_KEY + "': ['default.cc'],",
        "    }))");

    ImmutableList<Label> valueLabels = ImmutableList.of(
        Label.create("@//x", "a.cc"), Label.create("@//x", "b.cc"),
        Label.create("@//x", "always.cc"), Label.create("@//x", "c.cc"),
        Label.create("@//x", "d.cc"), Label.create("@//x", "default.cc"));
    ImmutableList<Label> keyLabels = ImmutableList.of(
        Label.create("@//conditions", "a"), Label.create("@//conditions", "b"),
        Label.create("@//conditions", "c"), Label.create("@//conditions", "d"));

    AggregatingAttributeMapper mapper = AggregatingAttributeMapper.of(rule);
    assertThat(mapper.getReachableLabels("srcs", true))
        .containsExactlyElementsIn(Iterables.concat(valueLabels, keyLabels));
    assertThat(mapper.getReachableLabels("srcs", false)).containsExactlyElementsIn(valueLabels);
  }

  @Test
  public void testGetReachableLabelsWithDefaultValues() throws Exception {
    Rule rule = scratchRule("a", "myrule",
        "cc_binary(name = 'myrule',",
        "    srcs = [],",
        "    malloc = select({",
        "        '//conditions:a': None,",
        "    }))");

    AggregatingAttributeMapper mapper = AggregatingAttributeMapper.of(rule);
    assertThat(mapper.getReachableLabels("malloc", true))
        .containsExactly(
            getDefaultMallocLabel(rule), Label.create("@//conditions", "a"));
  }

  @Test
  public void testDuplicateCheckOnNullValues() throws Exception {
    if (TestConstants.THIS_IS_BAZEL) {
      return;
    }
    Rule rule = scratchRule("x", "main",
        "java_binary(",
        "    name = 'main',",
        "    srcs = ['main.java'])");
    AggregatingAttributeMapper mapper = AggregatingAttributeMapper.of(rule);
    Attribute launcherAttribute = mapper.getAttributeDefinition("launcher");
    assertNull(mapper.get(launcherAttribute.getName(), launcherAttribute.getType()));
    assertThat(mapper.checkForDuplicateLabels(launcherAttribute))
        .containsExactlyElementsIn(ImmutableList.of());
  }

  /**
   * Custom rule to support testing over computed defaults.
   */
  public static final class RuleWithComputedDefaults
      implements RuleDefinition, RuleConfiguredTargetFactory {
    @Override
    public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment env) {
      return builder
          .add(attr("configurable1", STRING))
          .add(attr("configurable2", STRING))
          .add(attr("nonconfigurable", STRING).nonconfigurable("that's the point"))
          .add(attr("$computed_default_with_configurable_deps", STRING).value(
              new Attribute.ComputedDefault("configurable1", "configurable2") {
                @Override
                public Object getDefault(AttributeMap rule) {
                  return Joiner.on(" ").join(
                      rule.get("configurable1", STRING),
                      rule.get("configurable2", STRING),
                      rule.get("nonconfigurable", STRING)
                  );
                }
              }))
          .add(attr("$computed_default_without_configurable_deps", STRING).value(
              new Attribute.ComputedDefault() {
                @Override
                public Object getDefault(AttributeMap rule) {
                  return rule.get("nonconfigurable", STRING);
                }
              }))
          .build();
    }

    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("rule_with_computed_defaults")
          .ancestors(BaseRuleClasses.BaseRule.class)
          .factoryClass(UnknownRuleConfiguredTarget.class)
          .build();
    }

    @Override
    public ConfiguredTarget create(RuleContext ruleContext) throws InterruptedException {
      throw new UnsupportedOperationException();
    }
  }

  @Override
  protected ConfiguredRuleClassProvider getRuleClassProvider() {
    ConfiguredRuleClassProvider.Builder builder =
        new ConfiguredRuleClassProvider.Builder()
            .addRuleDefinition(new RuleWithComputedDefaults());
    TestRuleClassProvider.addStandardRules(builder);
    return builder.build();
  }

  @Test
  public void testComputedDefaultWithConfigurableDeps() throws Exception {
    Rule rule = scratchRule("x", "bb",
        "rule_with_computed_defaults(",
        "    name = 'bb',",
        "    configurable1 = select({':a': 'of', ':b': 'from'}),",
        "    configurable2 = select({':a': 'this', ':b': 'the'}),",
        "    nonconfigurable = 'bottom')");
    assertThat(AggregatingAttributeMapper.of(rule)
        .visitAttribute("$computed_default_with_configurable_deps", STRING))
        .containsExactly("of this bottom", "from this bottom", "of the bottom", "from the bottom");
  }

  @Test
  public void testComputedDefaultWithoutConfigurableDeps() throws Exception {
    Rule rule = scratchRule("x", "bb",
        "rule_with_computed_defaults(",
        "    name = 'bb',",
        "    nonconfigurable = 'swim up')");
    assertThat(AggregatingAttributeMapper.of(rule)
        .visitAttribute("$computed_default_without_configurable_deps", STRING))
        .containsExactly("swim up");
  }

}
