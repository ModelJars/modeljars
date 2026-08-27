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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class HuggingFaceContributionService implements ContributionService {
  private static final URI HUGGING_FACE = URI.create("https://huggingface.co");
  private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>;\\s*rel=\"next\"");
  private static final Set<String> CAPABILITY_TAGS =
      Set.of(
          "chat",
          "text-generation",
          "text-embedding",
          "feature-extraction",
          "sentence-similarity",
          "text-to-speech",
          "audio");

  private final HttpTransport transport;
  private final ObjectMapper json = new ObjectMapper();

  HuggingFaceContributionService() {
    this(new JavaHttpTransport());
  }

  HuggingFaceContributionService(HttpTransport transport) {
    this.transport = Objects.requireNonNull(transport, "transport");
  }

  @Override
  public ContributionDraft prepare(ContributionRequest request)
      throws IOException, InterruptedException {
    ContributionSource source = ContributionSource.parse(request.source());
    String[] repository = source.repository().split("/", 2);
    URI metadataUri =
        HUGGING_FACE.resolve(
            "/api/models/"
                + segment(repository[0])
                + "/"
                + segment(repository[1])
                + "/revision/"
                + segment(request.revision()));
    JsonNode metadata = parse(requiredGet(metadataUri), metadataUri);
    String revision = requiredText(metadata, "sha", "Hugging Face repository response");
    if (!revision.matches("[a-f0-9]{40,64}")) {
      throw new IOException("Hugging Face returned a mutable or invalid revision: " + revision);
    }

    List<RemoteFile> tree = fetchTree(source.repository(), revision);
    List<RemoteFile> selected = selectFiles(tree, request.files());
    List<ContributionFile> files = new ArrayList<>();
    for (RemoteFile file : selected) {
      files.add(resolveFile(source.repository(), revision, file));
    }

    String format = detectFormat(selected);
    Optional<String> architecture = optionalText(metadata.path("config"), "model_type");
    Optional<String> upstreamLicense =
        optionalText(metadata.path("cardData"), "license")
            .map(HuggingFaceContributionService::spdxLicense);
    List<String> capabilities =
        request.capabilities().isEmpty()
            ? upstreamCapabilities(metadata)
            : distinct(request.capabilities());
    String repositoryName = repository[1];
    String name =
        format.equals("gguf") || format.equals("cact")
            ? selected.stream()
                .filter(file -> file.path().toLowerCase(Locale.ROOT).endsWith("." + format))
                .findFirst()
                .map(RemoteFile::path)
                .map(HuggingFaceContributionService::baseName)
                .orElse(repositoryName)
            : repositoryName + " Safetensors";

    return new ContributionDraft(
        source.repository(),
        HUGGING_FACE.resolve('/' + source.repository()),
        revision,
        name,
        format,
        architecture,
        request.license().or(() -> upstreamLicense),
        capabilities,
        request.domains().isEmpty() ? List.of("general") : distinct(request.domains()),
        files);
  }

  private List<RemoteFile> fetchTree(String repository, String revision)
      throws IOException, InterruptedException {
    String[] parts = repository.split("/", 2);
    URI next =
        HUGGING_FACE.resolve(
            "/api/models/"
                + segment(parts[0])
                + "/"
                + segment(parts[1])
                + "/tree/"
                + segment(revision)
                + "?recursive=true&expand=true");
    List<RemoteFile> files = new ArrayList<>();
    while (next != null) {
      HttpResponse response = requiredGet(next);
      JsonNode page = parse(response, next);
      if (!page.isArray())
        throw new IOException("Hugging Face tree response is not an array: " + next);
      for (JsonNode entry : page) {
        if (!"file".equals(entry.path("type").asText())) continue;
        String path = requiredText(entry, "path", "Hugging Face tree entry");
        long size = entry.path("size").asLong(-1);
        if (size <= 0) throw new IOException("Hugging Face reported an invalid size for " + path);
        Optional<String> lfsSha = optionalText(entry.path("lfs"), "oid");
        files.add(new RemoteFile(path, size, lfsSha));
      }
      next = nextPage(response.headers());
    }
    return List.copyOf(files);
  }

  private static URI nextPage(Map<String, List<String>> headers) throws IOException {
    for (Map.Entry<String, List<String>> header : headers.entrySet()) {
      if (!header.getKey().equalsIgnoreCase("link")) continue;
      for (String value : header.getValue()) {
        Matcher matcher = NEXT_LINK.matcher(value);
        if (matcher.find()) {
          URI next = URI.create(matcher.group(1));
          if (!"https".equals(next.getScheme()) || !"huggingface.co".equals(next.getHost())) {
            throw new IOException("Hugging Face pagination attempted to leave huggingface.co");
          }
          return next;
        }
      }
    }
    return null;
  }

  private List<RemoteFile> selectFiles(List<RemoteFile> tree, List<String> requested) {
    Map<String, RemoteFile> byPath =
        tree.stream().collect(java.util.stream.Collectors.toMap(RemoteFile::path, value -> value));
    if (!requested.isEmpty()) {
      List<RemoteFile> selected =
          requested.stream()
              .distinct()
              .map(
                  path -> {
                    RemoteFile file = byPath.get(path);
                    if (file == null)
                      throw new IllegalArgumentException(
                          "File is not present at the pinned revision: " + path);
                    return file;
                  })
              .toList();
      if (selected.stream()
          .anyMatch(file -> file.path().toLowerCase(Locale.ROOT).endsWith(".safetensors"))) {
        return standardSafetensorsFiles(byPath);
      }
      return selected;
    }

    List<RemoteFile> gguf =
        tree.stream()
            .filter(file -> file.path().toLowerCase(Locale.ROOT).endsWith(".gguf"))
            .toList();
    List<RemoteFile> cact =
        tree.stream()
            .filter(file -> file.path().toLowerCase(Locale.ROOT).endsWith(".cact"))
            .toList();
    boolean standardSafetensors =
        byPath.containsKey("model.safetensors")
            || byPath.containsKey("model.safetensors.index.json");
    int availableFormats =
        (gguf.isEmpty() ? 0 : 1) + (cact.isEmpty() ? 0 : 1) + (standardSafetensors ? 1 : 0);
    if (availableFormats > 1) {
      throw new IllegalArgumentException(
          "Repository contains more than one supported model format; choose one with --file");
    }
    if (standardSafetensors) return standardSafetensorsFiles(byPath);
    if (gguf.size() == 1) return gguf;
    if (cact.size() == 1) return cact;
    if (gguf.size() > 1) {
      throw new IllegalArgumentException(
          "Repository contains multiple GGUF files; choose one with --file: "
              + gguf.stream().map(RemoteFile::path).sorted().limit(12).toList());
    }
    if (standardSafetensors) return standardSafetensorsFiles(byPath);
    if (cact.size() > 1) {
      throw new IllegalArgumentException(
          "Repository contains multiple CACT files; choose one with --file: "
              + cact.stream().map(RemoteFile::path).sorted().limit(12).toList());
    }
    throw new IllegalArgumentException(
        "No single GGUF or CACT file, or standard Safetensors checkpoint, was found; "
            + "select files with --file");
  }

  private static List<RemoteFile> standardSafetensorsFiles(Map<String, RemoteFile> byPath) {
    List<String> required = List.of("config.json", "tokenizer.json", "tokenizer_config.json");
    for (String path : required) {
      if (!byPath.containsKey(path)) {
        throw new IllegalArgumentException(
            "Standard Safetensors contribution is missing required file: " + path);
      }
    }
    LinkedHashSet<RemoteFile> selected = new LinkedHashSet<>();
    selected.add(byPath.get("config.json"));
    if (byPath.containsKey("model.safetensors")) {
      selected.add(byPath.get("model.safetensors"));
    } else {
      RemoteFile index = byPath.get("model.safetensors.index.json");
      if (index == null)
        throw new IllegalArgumentException("Safetensors checkpoint has no weights or index");
      selected.add(index);
      byPath.values().stream()
          .filter(file -> file.path().matches("model-\\d+-of-\\d+\\.safetensors"))
          .sorted(Comparator.comparing(RemoteFile::path))
          .forEach(selected::add);
      if (selected.size() == 2)
        throw new IllegalArgumentException("Safetensors index has no checkpoint shards");
    }
    selected.add(byPath.get("tokenizer.json"));
    selected.add(byPath.get("tokenizer_config.json"));
    return List.copyOf(selected);
  }

  private ContributionFile resolveFile(String repository, String revision, RemoteFile file)
      throws IOException, InterruptedException {
    String sha256;
    if (file.lfsSha256().isPresent()) {
      sha256 = file.lfsSha256().orElseThrow();
      if (!sha256.matches("[a-f0-9]{64}")) {
        throw new IOException("Hugging Face LFS digest is not SHA-256 for " + file.path());
      }
    } else {
      URI download = resolveUri(repository, revision, file.path());
      byte[] bytes = requiredGet(download).body();
      if (bytes.length != file.sizeBytes()) {
        throw new IOException(
            "Downloaded byte count does not match Hugging Face metadata for " + file.path());
      }
      sha256 = sha256(bytes);
    }
    return new ContributionFile(file.path(), role(file.path()), sha256, file.sizeBytes());
  }

  private static URI resolveUri(String repository, String revision, String path) {
    String encodedPath =
        java.util.Arrays.stream(path.split("/", -1))
            .map(HuggingFaceContributionService::segment)
            .collect(java.util.stream.Collectors.joining("/"));
    return HUGGING_FACE.resolve(
        '/' + repository + "/resolve/" + segment(revision) + '/' + encodedPath);
  }

  private HttpResponse requiredGet(URI uri) throws IOException, InterruptedException {
    HttpResponse response = transport.get(uri);
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IOException(
          "Hugging Face request failed with HTTP " + response.statusCode() + ": " + uri);
    }
    return response;
  }

  private JsonNode parse(HttpResponse response, URI uri) throws IOException {
    try {
      return json.readTree(response.body());
    } catch (IOException failure) {
      throw new IOException("Hugging Face returned invalid JSON: " + uri, failure);
    }
  }

  private static String requiredText(JsonNode node, String field, String context)
      throws IOException {
    return optionalText(node, field)
        .orElseThrow(() -> new IOException(context + " is missing " + field));
  }

  private static Optional<String> optionalText(JsonNode node, String field) {
    if (node == null || node.isMissingNode() || node.isNull()) return Optional.empty();
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) return Optional.empty();
    return Optional.of(value.asText());
  }

  private static List<String> upstreamCapabilities(JsonNode metadata) {
    LinkedHashSet<String> capabilities = new LinkedHashSet<>();
    optionalText(metadata, "pipeline_tag").ifPresent(capabilities::add);
    JsonNode tags = metadata.path("cardData").path("tags");
    if (tags.isArray()) {
      for (JsonNode tag : tags) {
        if (tag.isTextual() && CAPABILITY_TAGS.contains(tag.asText()))
          capabilities.add(tag.asText());
      }
    }
    return List.copyOf(capabilities);
  }

  private static String detectFormat(List<RemoteFile> files) {
    if (files.stream().anyMatch(file -> file.path().toLowerCase(Locale.ROOT).endsWith(".gguf")))
      return "gguf";
    if (files.stream().anyMatch(file -> file.path().toLowerCase(Locale.ROOT).endsWith(".cact")))
      return "cact";
    if (files.stream()
        .anyMatch(file -> file.path().toLowerCase(Locale.ROOT).endsWith(".safetensors")))
      return "safetensors";
    throw new IllegalArgumentException(
        "Selected files do not contain GGUF, CACT, or Safetensors model weights");
  }

  private static String role(String path) {
    if (path.equals("config.json")) return "model-configuration";
    if (path.equals("tokenizer.json")) return "tokenizer";
    if (path.equals("tokenizer_config.json")) return "tokenizer-configuration";
    if (path.endsWith(".index.json")) return "weights-index";
    if (path.endsWith(".gguf") || path.endsWith(".cact") || path.endsWith(".safetensors"))
      return "model-weights";
    return "supporting-file";
  }

  private static String spdxLicense(String value) {
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "apache-2.0" -> "Apache-2.0";
      case "mit" -> "MIT";
      case "bsd-3-clause" -> "BSD-3-Clause";
      default -> value;
    };
  }

  private static String baseName(String path) {
    int slash = path.lastIndexOf('/');
    String filename = slash < 0 ? path : path.substring(slash + 1);
    int dot = filename.lastIndexOf('.');
    return dot > 0 ? filename.substring(0, dot) : filename;
  }

  private static List<String> distinct(List<String> values) {
    return values.stream().map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();
  }

  private static String segment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  @FunctionalInterface
  interface HttpTransport {
    HttpResponse get(URI uri) throws IOException, InterruptedException;
  }

  record HttpResponse(int statusCode, Map<String, List<String>> headers, byte[] body) {
    HttpResponse {
      headers = Map.copyOf(headers);
      body = body.clone();
    }

    @Override
    public byte[] body() {
      return body.clone();
    }
  }

  private static final class JavaHttpTransport implements HttpTransport {
    private final HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public HttpResponse get(URI uri) throws IOException, InterruptedException {
      HttpRequest request =
          HttpRequest.newBuilder(uri)
              .timeout(Duration.ofMinutes(5))
              .header("Accept", "application/json, application/octet-stream")
              .header("User-Agent", "ModelJars-CLI/0.1 contribution-intake")
              .GET()
              .build();
      java.net.http.HttpResponse<byte[]> response =
          client.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
      return new HttpResponse(response.statusCode(), response.headers().map(), response.body());
    }
  }

  private record RemoteFile(String path, long sizeBytes, Optional<String> lfsSha256) {}
}
