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
package org.modeljars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModelComponentQualificationRegistryTest {
  private static final String WEIGHTS_SHA = "a".repeat(64);
  private static final String CONFIG_SHA = "b".repeat(64);
  private static final String NOTICE_SHA = "c".repeat(64);
  private static final String LICENSE_SHA = "d".repeat(64);

  @Test
  void bindsAQualifiedComponentToTheExactBaseAndCompleteArtifactBundle() {
    ModelJarDescriptor base = descriptor("base", "e".repeat(64), List.of(), Set.of("chat"));
    ModelJarDescriptor component = component();
    ModelComponentQualificationRegistry registry =
        ModelComponentQualificationRegistry.fromProperties(properties(true));

    var qualification = registry.qualificationFor(component, base).orElseThrow();

    assertEquals("activated-adapter-component-v1", registry.policyVersion());
    assertEquals("1".repeat(40), registry.modelsRevision());
    assertEquals("2".repeat(40), registry.evidenceRevision());
    assertEquals("adapter", qualification.modelId());
    assertEquals(256, qualification.minimumSharedPrefixTokens());
    assertEquals(1, registry.qualifiedModels());
    assertEquals(0, registry.rejectedModels());
  }

  @Test
  void refusesStaleComponentBytesOrTheWrongBase() {
    ModelJarDescriptor component = component();
    ModelComponentQualificationRegistry registry =
        ModelComponentQualificationRegistry.fromProperties(properties(true));

    assertTrue(
        registry
            .qualificationFor(
                component, descriptor("other-base", "e".repeat(64), List.of(), Set.of("chat")))
            .isEmpty());
    assertTrue(
        registry
            .qualificationFor(
                component, descriptor("base", "9".repeat(64), List.of(), Set.of("chat")))
            .isEmpty());
    List<ModelArtifactFile> changedFiles =
        component.files().stream()
            .map(
                file ->
                    file.equals(component.files().getFirst())
                        ? file(file.path(), file.role(), "f".repeat(64), file.sizeBytes())
                        : file)
            .toList();
    ModelJarDescriptor changed =
        descriptor("adapter", "f".repeat(64), changedFiles, Set.of("composition-component"));
    assertTrue(
        registry
            .qualificationFor(
                changed, descriptor("base", "e".repeat(64), List.of(), Set.of("chat")))
            .isEmpty());
  }

  @Test
  void neverReturnsRejectedEvidenceAndValidatesPublishedCounts() {
    ModelComponentQualificationRegistry rejected =
        ModelComponentQualificationRegistry.fromProperties(properties(false));
    assertTrue(
        rejected
            .qualificationFor(
                component(), descriptor("base", "e".repeat(64), List.of(), Set.of("chat")))
            .isEmpty());

    Properties badCounts = properties(true);
    badCounts.setProperty("modeljars.componentQualifications.qualifiedModels", "0");
    assertThrows(
        ModelJarException.class,
        () -> ModelComponentQualificationRegistry.fromProperties(badCounts));
  }

  private static ModelJarDescriptor component() {
    List<ModelArtifactFile> files =
        List.of(
            file("adapter_model.safetensors", "adapter-weights", WEIGHTS_SHA, 1024),
            file("models-activated-lora.json", "adapter-configuration", CONFIG_SHA, 512),
            file("NOTICE", "attribution-notice", NOTICE_SHA, 128),
            file("LICENSE", "license", LICENSE_SHA, 256));
    return descriptor("adapter", WEIGHTS_SHA, files, Set.of("composition-component"));
  }

  private static ModelJarDescriptor descriptor(
      String id, String sha256, List<ModelArtifactFile> files, Set<String> capabilities) {
    return new ModelJarDescriptor(
        id,
        "fixture/" + id,
        ModelJarCoordinate.parse("org.modeljars.fixture:" + id + ":1.0.0"),
        ModelVersion.parse("1.0.0"),
        "fixture",
        files.isEmpty() ? "gguf" : "safetensors",
        files.isEmpty() ? "qwen3" : "qwen3-activated-lora",
        files.isEmpty() ? "Q8_0" : "F32",
        Optional.empty(),
        Optional.empty(),
        Optional.of(URI.create("https://example.invalid/" + id)),
        files.isEmpty()
            ? Optional.of(URI.create("https://example.invalid/" + id + "/model.gguf"))
            : Optional.of(files.getFirst().downloadUri()),
        Optional.of("3".repeat(40)),
        Optional.of(sha256),
        Optional.of(files.isEmpty() ? 2048L : files.getFirst().sizeBytes()),
        Optional.of("Apache-2.0"),
        capabilities,
        files.isEmpty()
            ? Set.of("chat-template")
            : Set.of("multi-file-artifact", "activated-lora-adapter"),
        files,
        Map.of("pure-java", true),
        Optional.of(id),
        Optional.of("fixture"),
        Optional.empty(),
        Set.of(),
        ModelDimensions.unknown());
  }

  private static ModelArtifactFile file(String path, String role, String sha256, long sizeBytes) {
    return new ModelArtifactFile(
        path, role, URI.create("https://example.invalid/adapter/" + path), sha256, sizeBytes);
  }

  private static Properties properties(boolean qualified) {
    List<ModelArtifactFile> files = component().files();
    String bundleIdentity =
        files.stream()
            .sorted(java.util.Comparator.comparing(ModelArtifactFile::path))
            .map(file -> file.path() + "\t" + file.sizeBytes() + "\t" + file.sha256() + "\n")
            .collect(java.util.stream.Collectors.joining());
    String bundleSha =
        HexFormat.of().formatHex(digest(bundleIdentity.getBytes(StandardCharsets.UTF_8)));
    Properties properties = new Properties();
    properties.setProperty("modeljars.componentQualifications.schemaVersion", "1");
    properties.setProperty("modeljars.componentQualifications.generatedAt", "2026-09-13T16:00:00Z");
    properties.setProperty(
        "modeljars.componentQualifications.policyVersion", "activated-adapter-component-v1");
    properties.setProperty("modeljars.componentQualifications.modelsRevision", "1".repeat(40));
    properties.setProperty("modeljars.componentQualifications.evidenceRevision", "2".repeat(40));
    properties.setProperty(
        "modeljars.componentQualifications.qualifiedModels", qualified ? "1" : "0");
    properties.setProperty(
        "modeljars.componentQualifications.rejectedModels", qualified ? "0" : "1");
    String prefix = "componentQualification.adapter.";
    properties.setProperty(prefix + "baseModelId", "base");
    properties.setProperty(prefix + "baseArtifactSha256", "e".repeat(64));
    properties.setProperty(prefix + "baseArtifactSizeBytes", "2048");
    properties.setProperty(prefix + "artifactSha256", WEIGHTS_SHA);
    properties.setProperty(prefix + "artifactSizeBytes", "1024");
    properties.setProperty(prefix + "artifactBundleSizeBytes", "1920");
    properties.setProperty(prefix + "artifactBundleSha256", bundleSha);
    properties.setProperty(prefix + "minimumSharedPrefixTokens", "256");
    properties.setProperty(
        prefix + "reportUri",
        "https://raw.githubusercontent.com/integrallis/models/" + "2".repeat(40) + "/report.json");
    properties.setProperty(prefix + "reportSha256", "4".repeat(64));
    properties.setProperty(prefix + "qualified", Boolean.toString(qualified));
    properties.setProperty(prefix + "artifactFile.count", Integer.toString(files.size()));
    for (int index = 0; index < files.size(); index++) {
      ModelArtifactFile file = files.get(index);
      String filePrefix = prefix + "artifactFile." + "%03d".formatted(index) + ".";
      properties.setProperty(filePrefix + "path", file.path());
      properties.setProperty(filePrefix + "role", file.role());
      properties.setProperty(filePrefix + "sha256", file.sha256());
      properties.setProperty(filePrefix + "sizeBytes", Long.toString(file.sizeBytes()));
    }
    return properties;
  }

  private static byte[] digest(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }
}
