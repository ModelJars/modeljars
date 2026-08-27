package org.modeljars.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HuggingFaceContributionServiceTest {
  @Test
  void pinsARevisionAndBuildsACompleteStandardSafetensorsManifest() throws Exception {
    String revision = "7".repeat(40);
    Map<String, byte[]> smallFiles =
        Map.of(
            "config.json", "config".getBytes(StandardCharsets.UTF_8),
            "tokenizer.json", "tokenizer".getBytes(StandardCharsets.UTF_8),
            "tokenizer_config.json", "tokenizer config".getBytes(StandardCharsets.UTF_8));
    String modelJson =
        """
        {
          "sha": "%s",
          "pipeline_tag": "text-generation",
          "cardData": {"license": "apache-2.0", "tags": ["chat"]},
          "config": {"model_type": "qwen2"}
        }
        """.formatted(revision);
    String treeJson =
        """
        [
          {"type":"file","path":"config.json","size":6,"oid":"a"},
          {"type":"file","path":"model.safetensors","size":988097824,"oid":"b","lfs":{"oid":"%s","size":988097824}},
          {"type":"file","path":"tokenizer.json","size":9,"oid":"c"},
          {"type":"file","path":"tokenizer_config.json","size":16,"oid":"d"}
        ]
        """.formatted("f".repeat(64));
    HuggingFaceContributionService.HttpTransport transport =
        uri -> {
          String path = uri.getPath();
          if (path.startsWith("/api/models/Qwen/Demo/revision/")) {
            return response(modelJson);
          }
          if (path.startsWith("/api/models/Qwen/Demo/tree/")) {
            return response(treeJson);
          }
          String filename = path.substring(path.lastIndexOf('/') + 1);
          return new HuggingFaceContributionService.HttpResponse(
              200, Map.of(), smallFiles.get(filename));
        };
    ContributionService service = new HuggingFaceContributionService(transport);

    ContributionDraft draft =
        service.prepare(
            new ContributionRequest(
                "Qwen/Demo", "main", List.of(), Optional.empty(), List.of("general"), List.of()));

    assertEquals(revision, draft.revision());
    assertEquals("safetensors", draft.format());
    assertEquals(Optional.of("qwen2"), draft.architectureHint());
    assertEquals(Optional.of("Apache-2.0"), draft.license());
    assertEquals(List.of("text-generation", "chat"), draft.capabilities());
    assertEquals(
        List.of("config.json", "model.safetensors", "tokenizer.json", "tokenizer_config.json"),
        draft.files().stream().map(ContributionFile::path).toList());
    assertEquals("f".repeat(64), draft.files().get(1).sha256());
    assertEquals(sha256(smallFiles.get("config.json")), draft.files().get(0).sha256());
  }

  @Test
  void discoversASingleCactArtifactWithoutManualFileSelection() throws Exception {
    String revision = "9".repeat(40);
    String artifactSha = "a".repeat(64);
    String modelJson =
        """
        {
          "sha": "%s",
          "pipeline_tag": "text-generation",
          "cardData": {"license": "apache-2.0", "tags": ["chat"]}
        }
        """.formatted(revision);
    String treeJson =
        """
        [
          {"type":"file","path":"needle2.cact","size":13737807,
           "lfs":{"oid":"%s","size":13737807}}
        ]
        """.formatted(artifactSha);
    HuggingFaceContributionService.HttpTransport transport =
        uri ->
            uri.getPath().contains("/tree/") ? response(treeJson) : response(modelJson);

    ContributionDraft draft =
        new HuggingFaceContributionService(transport)
            .prepare(
                new ContributionRequest(
                    "Cactus-Compute/needle2",
                    "main",
                    List.of(),
                    Optional.empty(),
                    List.of("tool-use"),
                    List.of("text-generation", "chat", "tool-calling")));

    assertEquals("cact", draft.format());
    assertEquals("needle2", draft.name());
    assertEquals(List.of("needle2.cact"), draft.files().stream().map(ContributionFile::path).toList());
    assertEquals("model-weights", draft.files().getFirst().role());
    assertEquals(artifactSha, draft.files().getFirst().sha256());
  }

  private static HuggingFaceContributionService.HttpResponse response(String body) {
    return new HuggingFaceContributionService.HttpResponse(
        200, Map.of(), body.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256(byte[] value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }
}
