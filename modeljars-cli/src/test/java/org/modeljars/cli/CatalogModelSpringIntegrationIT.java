/*
 * Copyright 2025-2026 Integrallis Software, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.modeljars.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.integrallis.models.api.InferenceBackend;
import com.integrallis.models.api.catalog.DiscoveredModel;
import com.integrallis.models.spring.boot.ModelsAutoConfiguration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.modeljars.ModelJarsCatalog;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The whole chain, with nothing stubbed: a catalog id in configuration to a generated answer.
 *
 * <p>This is the only test scope where the ModelJars catalog and the Models Spring starter share a
 * classpath, so it is the only place the claim can actually be checked. Everything in between is
 * the shipped path -- {@code ModelJarsCatalog.locate} finds the artifact, {@code CatalogBackends}
 * resolves it across the installed catalogs, {@code PureJavaBackend.loadAutomatic} opens it with
 * the native shims underneath, the starter adapts it to a Spring AI {@code ChatModel}, and the
 * {@code ChatClient.Builder} the starter contributes builds the client that answers.
 *
 * <p>The unit tests on either side of that chain all substitute something: the models-side tests
 * use a test provider because ModelJars could not implement {@code locate} until now, and the
 * ModelJars-side tests never open a model. Neither would catch the two halves disagreeing.
 *
 * <p>The model is chosen as the smallest installed chat model rather than named, so this runs on
 * whatever a machine happens to have, and skips with a reason when it has nothing.
 */
@Tag("integration")
class CatalogModelSpringIntegrationIT {

  @Test
  void answersAPromptFromAModelNamedOnlyByItsCatalogIdInConfiguration() {
    Optional<DiscoveredModel> smallest = smallestInstalledChatModel();
    assumeTrue(smallest.isPresent(), "no chat ModelJar is installed on this machine");
    DiscoveredModel model = smallest.orElseThrow();

    // Proof the id is real before it is used, so a failure below cannot be a bad fixture.
    assertThat(new ModelJarsCatalog().locate(model.id())).isPresent();

    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ModelsAutoConfiguration.class))
        .withPropertyValues(
            "integrallis.models.catalog-model=" + model.id(),
            "integrallis.models.chat-template=chatml-no-think",
            "integrallis.models.sampling.temperature=0.0",
            "integrallis.models.sampling.max-tokens=8")
        .run(
            context -> {
              assertThat(context).hasNotFailed();

              // Every bean in the chain, in order, so a break names its own link.
              assertThat(context).hasSingleBean(InferenceBackend.class);
              assertThat(context).hasSingleBean(ChatModel.class);
              assertThat(context).hasSingleBean(ChatClient.Builder.class);

              String answer =
                  context
                      .getBean(ChatClient.Builder.class)
                      .build()
                      .prompt("Say READY")
                      .call()
                      .content();

              // Greedy decoding, so this is stable run to run. What is asserted is that tokens came
              // out of weights no application code ever named by path.
              assertThat(answer).isNotNull();
              assertThat(answer.strip()).isNotEmpty();
              // Prose, not a tool call, for the plain chat model this picks.
              assertThat(answer).doesNotContain("<tool_call>");
              System.out.println("end-to-end answered from " + model.id() + ": " + answer.strip());
            });
  }

  @Test
  void refusesACatalogIdThatIsNotInstalledInsteadOfDownloadingIt() {
    // The other half of the contract, and the one a user is more likely to hit. A lookup must not
    // turn into a multi-gigabyte fetch, so an uninstalled id has to fail and say so.
    Optional<String> uninstalled = someUninstalledCatalogId();
    assumeTrue(uninstalled.isPresent(), "every catalogued model is installed on this machine");

    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ModelsAutoConfiguration.class))
        .withPropertyValues("integrallis.models.catalog-model=" + uninstalled.orElseThrow())
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining(uninstalled.orElseThrow())
                  .hasStackTraceContaining("no installed catalog can open")
                  // Advice a user can act on, and none they cannot: this refusal lists the ids
                  // that are openable rather than telling them to fetch something.
                  .hasStackTraceContaining("openable ids are");
            });
  }

  /**
   * The smallest installed chat model, preferring one that is not tool-trained.
   *
   * <p>Smallest first is this catalog's own founding principle, and it also keeps the gate cheap.
   * The tool-use preference is not cosmetic: a tool-trained model answers a plain prompt with a
   * tool call, which still proves tokens were produced but lets the assertion say nothing about
   * them. With a plain chat model the test can require actual prose.
   */
  private static Optional<DiscoveredModel> smallestInstalledChatModel() {
    List<DiscoveredModel> chat =
        new ModelJarsCatalog()
            .discover().stream()
                .filter(model -> model.tags().contains("chat"))
                .filter(model -> model.sizeBytes() > 0)
                .sorted((left, right) -> Long.compare(left.sizeBytes(), right.sizeBytes()))
                .toList();
    return chat.stream()
        .filter(model -> !model.tags().contains("tool-use"))
        .findFirst()
        .or(() -> chat.stream().findFirst());
  }

  private static Optional<String> someUninstalledCatalogId() {
    ModelJarsCatalog catalog = new ModelJarsCatalog();
    List<String> installed = catalog.discover().stream().map(DiscoveredModel::id).toList();
    return org.modeljars.ModelJarRegistry.fromClasspath().descriptors().stream()
        .map(org.modeljars.ModelJarDescriptor::alias)
        .filter(alias -> !installed.contains(alias))
        .filter(alias -> catalog.locate(alias).isEmpty())
        .findFirst();
  }
}
