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

/**
 * Auditable evidence that one exact typed-decision artifact answers accurately, and how far its
 * published confidence can be trusted.
 *
 * <p>The counterpart to {@link ModelRagQualification} and {@link ModelEmbeddingQualification} for a
 * model that produces neither generated text nor vectors — only a chosen option and a probability.
 * {@code correctAnswerRate} is a pipeline metric and says nothing here.
 *
 * <p><b>Accuracy and calibration are separate findings and this record keeps them separate.</b> A
 * decision model can be reliably right about which option to pick while its probability means
 * nothing in the regime it is being used in, and a caller needs to know which of the two it is
 * holding. {@code tier} is that answer: {@code CALIBRATED_DECISION} may be thresholded, {@code
 * RANKED_DECISION} may be ranked and not thresholded.
 *
 * <p><b>Chance is recorded beside accuracy, always.</b> An accuracy figure over a cohort mixing
 * binary and six-way questions means very little alone — 0.60 is near-guessing on one and three
 * times chance on the other. A reader who has {@code accuracy} without {@code chanceAccuracy} has a
 * number they cannot interpret.
 *
 * <p>{@code uncalibratedBucketsExercised} exists so a pass cannot be mistaken for evidence. A
 * cohort with no question in a bucket the artifact declares uncalibrated never tested that promise,
 * and zero here says so rather than implying it held.
 *
 * @param modelId catalog alias of the qualified model
 * @param model display name and variant of the qualified model
 * @param backend normalized execution backend identifier
 * @param backendVersion exact backend version or build
 * @param policy identifier of the qualification policy that produced the tier
 * @param cohort stable identifier of the labelled decision cohort
 * @param cohortSha256 SHA-256 digest of the pinned cohort file
 * @param artifactSha256 SHA-256 digest of the qualified model artifact
 * @param artifactSizeBytes byte size of the qualified model artifact
 * @param report repository-relative path to the qualification evidence report
 * @param reportSha256 SHA-256 digest of the evidence report
 * @param tier what the model may be used for; only the calibrated tier exports a confidence quality
 * @param cases number of labelled decisions scored
 * @param chanceAccuracy accuracy a uniform random chooser would reach on this cohort's option
 *     counts
 * @param accuracy fraction of decisions whose chosen option matched the label
 * @param choiceAccuracy accuracy over the {@code choice} subset, negative when absent
 * @param noulAccuracy accuracy over the binary {@code noul} subset, negative when absent
 * @param scoreAccuracy accuracy over the {@code score} subset, negative when absent
 * @param calibrationError expected calibration error, NaN when nothing was calibrated enough to
 *     measure
 * @param uncalibratedAnswers answers the model itself reported as uncalibrated
 * @param uncalibratedBucketsExercised cohort questions falling in a bucket the artifact declares
 *     uncalibrated; zero means that promise was never tested
 * @param environment host and JVM identity the evidence was produced on
 */
public record ModelDecisionQualification(
    String modelId,
    String model,
    String backend,
    String backendVersion,
    String policy,
    String cohort,
    String cohortSha256,
    String artifactSha256,
    long artifactSizeBytes,
    String report,
    String reportSha256,
    String tier,
    int cases,
    double chanceAccuracy,
    double accuracy,
    double choiceAccuracy,
    double noulAccuracy,
    double scoreAccuracy,
    double calibrationError,
    int uncalibratedAnswers,
    int uncalibratedBucketsExercised,
    String environment) {

  /** The tier that may publish a confidence quality for callers to gate on. */
  public static final String TIER_CALIBRATED = "CALIBRATED_DECISION";

  /** The tier whose ranking is reliable and whose probability is not a fitted one. */
  public static final String TIER_RANKED = "RANKED_DECISION";

  public ModelDecisionQualification {
    modelId = requireText(modelId, "modelId");
    model = requireText(model, "model");
    backend = requireText(backend, "backend");
    backendVersion = requireText(backendVersion, "backendVersion");
    policy = requireText(policy, "policy");
    cohort = requireText(cohort, "cohort");
    cohortSha256 = requireDigest(cohortSha256, "cohortSha256");
    artifactSha256 = requireDigest(artifactSha256, "artifactSha256");
    report = requireText(report, "report");
    reportSha256 = requireDigest(reportSha256, "reportSha256");
    tier = requireText(tier, "tier");
    if (!TIER_CALIBRATED.equals(tier) && !TIER_RANKED.equals(tier)) {
      // An unqualified model has no record. A tier this code does not understand would otherwise
      // be published as though its meaning were known.
      throw new ModelJarException(
          "Unknown decision tier: "
              + tier
              + "; expected "
              + TIER_CALIBRATED
              + " or "
              + TIER_RANKED);
    }
    if (artifactSizeBytes <= 0) {
      throw new ModelJarException("artifactSizeBytes must be positive: " + artifactSizeBytes);
    }
    if (cases <= 0) {
      throw new ModelJarException("a qualification over no cases is not a qualification");
    }
    requireFraction(chanceAccuracy, "chanceAccuracy");
    requireFraction(accuracy, "accuracy");
    requireOptionalFraction(choiceAccuracy, "choiceAccuracy");
    requireOptionalFraction(noulAccuracy, "noulAccuracy");
    requireOptionalFraction(scoreAccuracy, "scoreAccuracy");
    if (accuracy <= chanceAccuracy) {
      // Not a threshold, a sanity check. A model no better than guessing has not been shown to
      // decide anything, whatever policy admitted it.
      throw new ModelJarException(
          "accuracy " + accuracy + " does not beat chance " + chanceAccuracy);
    }
    if (!Double.isNaN(calibrationError) && (calibrationError < 0.0 || calibrationError > 1.0)) {
      throw new ModelJarException("calibrationError must be NaN or in [0, 1]: " + calibrationError);
    }
    if (TIER_CALIBRATED.equals(tier) && Double.isNaN(calibrationError)) {
      throw new ModelJarException(
          "a CALIBRATED_DECISION record must carry a measured calibration error");
    }
    if (uncalibratedAnswers < 0 || uncalibratedBucketsExercised < 0) {
      throw new ModelJarException("counts must not be negative");
    }
    environment = requireText(environment, "environment");
  }

  /** Whether a model with this record may publish a confidence quality callers can gate on. */
  public boolean exportsConfidenceQuality() {
    return TIER_CALIBRATED.equals(tier);
  }

  /**
   * Whether this cohort ever exercised a bucket the artifact declares uncalibrated.
   *
   * <p>False means the honesty promise is untested rather than upheld.
   */
  public boolean testedUncalibratedReporting() {
    return uncalibratedBucketsExercised > 0;
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new ModelJarException(name + " must not be blank");
    }
    return value.strip();
  }

  private static String requireDigest(String value, String name) {
    String digest = requireText(value, name);
    if (!digest.matches("[0-9a-f]{64}")) {
      throw new ModelJarException(name + " must be a lowercase hex SHA-256: " + digest);
    }
    return digest;
  }

  private static void requireFraction(double value, String name) {
    if (!(value >= 0.0) || !(value <= 1.0)) {
      throw new ModelJarException(name + " must be in [0, 1]: " + value);
    }
  }

  /** A per-type accuracy, or any negative value meaning the cohort had none of that type. */
  private static void requireOptionalFraction(double value, String name) {
    if (value < 0.0) {
      return;
    }
    requireFraction(value, name);
  }
}
