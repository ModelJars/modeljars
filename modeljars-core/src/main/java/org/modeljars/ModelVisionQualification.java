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

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Exact two-file evidence for the bounded LLaVA VQAv2/TextVQA admission workload. Branch evidence
 * records its immutable source revision; it does not imply a released artifact.
 *
 * @param modelId catalog identity
 * @param backendVersion exact measured Models version and source revision
 * @param report immutable evidence location
 * @param reportSha256 evidence digest
 * @param corpusSha256 frozen case manifest digest
 * @param files complete text/projector snapshot
 * @param datasets paired task scores for both required datasets
 * @param tokenAgreement fraction of complete token sequences matching the reference
 * @param performance paired latency buckets
 * @param integrationPassed numerical, encoder and decoder integration verdict
 * @param qualified whether all backend admission gates passed
 */
public record ModelVisionQualification(
    String modelId,
    String backendVersion,
    URI report,
    String reportSha256,
    String corpusSha256,
    List<ModelArtifactFile> files,
    Map<String, Dataset> datasets,
    double tokenAgreement,
    List<Performance> performance,
    boolean integrationPassed,
    boolean qualified) {

  /**
   * One public dataset's VQA soft scores and image ablation.
   *
   * @param cases number of original image questions
   * @param candidateScore candidate mean soft score
   * @param referenceScore independent same-weight mean soft score
   * @param blankCases number of paired blank-image controls
   * @param imageContribution original minus blank score on the paired subset
   */
  public record Dataset(
      int cases,
      double candidateScore,
      double referenceScore,
      int blankCases,
      double imageContribution) {
    /** Rejects incomplete or nonfinite task evidence. */
    public Dataset {
      if (cases != 32 || blankCases != 8)
        throw new IllegalArgumentException("Vision corpus coverage differs");
      unit(candidateScore);
      unit(referenceScore);
      if (!Double.isFinite(imageContribution) || Math.abs(imageContribution) > 1)
        throw new IllegalArgumentException("Invalid image contribution");
    }
  }

  /**
   * A matched per-case latency comparison, including the encoder in prefill.
   *
   * @param dataset dataset name
   * @param caseId frozen case identity
   * @param warmups discarded warmups
   * @param repetitions measured repetitions
   * @param prefillRatio candidate/reference median prefill ratio
   * @param decodeRatio candidate/reference median decode ratio; 1 only for empty outputs in both
   *     arms
   * @param totalRatio candidate/reference median total ratio
   */
  public record Performance(
      String dataset,
      String caseId,
      int warmups,
      int repetitions,
      double prefillRatio,
      double decodeRatio,
      double totalRatio) {
    /** Validates shape, coverage and finite positive measurements. */
    public Performance {
      if (!Set.of("vqav2", "textvqa").contains(dataset)
          || caseId == null
          || caseId.isBlank()
          || warmups < 1
          || repetitions < 3)
        throw new IllegalArgumentException("Invalid vision performance coverage");
      for (double value : new double[] {prefillRatio, decodeRatio, totalRatio})
        if (!Double.isFinite(value) || value <= 0)
          throw new IllegalArgumentException("Invalid vision latency ratio");
    }

    boolean passed() {
      return prefillRatio <= 1.10 && decodeRatio <= 1.10 && totalRatio <= 1.10;
    }
  }

  /** Validates artifact binding and rejects a qualified claim that fails any gate. */
  public ModelVisionQualification {
    if (modelId == null || modelId.isBlank() || backendVersion == null || backendVersion.isBlank())
      throw new IllegalArgumentException("Model and backend identities required");
    Objects.requireNonNull(report, "report");
    if (!report.isAbsolute()) throw new IllegalArgumentException("Absolute report URI required");
    digest(reportSha256);
    digest(corpusSha256);
    files = List.copyOf(files);
    if (files.size() != 2
        || files.stream().map(ModelArtifactFile::path).distinct().count() != 2
        || !files.stream()
            .map(ModelArtifactFile::role)
            .collect(Collectors.toSet())
            .equals(Set.of("weights", "vision-projector")))
      throw new IllegalArgumentException("Exactly one text GGUF and one vision projector required");
    datasets = Map.copyOf(datasets);
    if (!datasets.keySet().equals(Set.of("vqav2", "textvqa")))
      throw new IllegalArgumentException("Both vision datasets required");
    unit(tokenAgreement);
    performance = List.copyOf(performance);
    if (performance.size() != 4
        || performance.stream().map(Performance::caseId).distinct().count() != 4
        || performance.stream().filter(p -> p.dataset().equals("vqav2")).count() != 2)
      throw new IllegalArgumentException(
          "Four distinct performance cases across both datasets required");
    if (qualified
        && (!integrationPassed
            || tokenAgreement < .99
            || datasets.get("vqav2").candidateScore() < .50
            || datasets.get("textvqa").candidateScore() < .20
            || datasets.get("vqav2").imageContribution() < .05
            || datasets.values().stream()
                .anyMatch(d -> d.referenceScore() - d.candidateScore() > .005 + 1e-12)
            || performance.stream().anyMatch(p -> !p.passed())))
      throw new IllegalArgumentException(
          "Qualified vision evidence must pass every registered gate");
  }

  /**
   * Checks both pinned artifacts, roles and the supported profile.
   *
   * @param descriptor marker to match
   * @return whether the marker describes exactly this measured pair
   */
  public boolean matches(ModelJarDescriptor descriptor) {
    return modelId.equals(descriptor.alias())
        && descriptor.format().equals("gguf")
        && descriptor.architecture().equals("llama")
        && descriptor.features().contains("llava-1.5")
        && descriptor.capabilities().contains("image-understanding")
        && descriptor.supportsBackend("rust-ffm")
        && identity(files).equals(identity(descriptor.files()));
  }

  private static Map<String, String> identity(List<ModelArtifactFile> files) {
    return files.stream()
        .collect(
            Collectors.toMap(
                ModelArtifactFile::path, f -> f.sha256() + ":" + f.sizeBytes() + ":" + f.role()));
  }

  private static void unit(double value) {
    if (!Double.isFinite(value) || value < 0 || value > 1)
      throw new IllegalArgumentException("Score must be in [0,1]");
  }

  private static void digest(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("SHA-256 required");
  }
}
