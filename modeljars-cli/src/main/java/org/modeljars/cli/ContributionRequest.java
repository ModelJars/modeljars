package org.modeljars.cli;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

record ContributionRequest(
    String source,
    String revision,
    List<String> files,
    Optional<String> license,
    List<String> domains,
    List<String> capabilities) {
  ContributionRequest {
    source = Objects.requireNonNull(source, "source").trim();
    revision = Objects.requireNonNull(revision, "revision").trim();
    files = List.copyOf(files);
    license = Objects.requireNonNull(license, "license").map(String::trim).filter(value -> !value.isEmpty());
    domains = List.copyOf(domains);
    capabilities = List.copyOf(capabilities);
    if (source.isEmpty()) throw new IllegalArgumentException("Contribution source must not be blank");
    if (revision.isEmpty()) throw new IllegalArgumentException("Contribution revision must not be blank");
  }
}
