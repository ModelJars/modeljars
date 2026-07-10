package org.modeljars;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Query used to select a model variant from a registry. */
public record ModelJarRequirement(
    String source,
    Optional<VersionRange> versionRange,
    Optional<String> variant,
    Optional<String> backend,
    Optional<String> capability) {
  public ModelJarRequirement {
    if (source == null || source.isBlank()) {
      throw new IllegalArgumentException("source must not be blank");
    }
    source = source.trim();
    versionRange = Objects.requireNonNull(versionRange, "versionRange");
    variant =
        Objects.requireNonNull(variant, "variant").map(value -> value.toLowerCase(Locale.ROOT));
    backend =
        Objects.requireNonNull(backend, "backend").map(value -> value.toLowerCase(Locale.ROOT));
    capability =
        Objects.requireNonNull(capability, "capability").map(value -> value.toLowerCase(Locale.ROOT));
  }

  public static Builder forSource(String source) {
    return new Builder(source);
  }

  public static final class Builder {
    private final String source;
    private VersionRange versionRange;
    private String variant;
    private String backend;
    private String capability;

    private Builder(String source) {
      this.source = source;
    }

    public Builder versionRange(String value) {
      this.versionRange = VersionRange.parse(value);
      return this;
    }

    public Builder variant(String value) {
      this.variant = value;
      return this;
    }

    public Builder backend(String value) {
      this.backend = value;
      return this;
    }

    public Builder capability(String value) {
      this.capability = value;
      return this;
    }

    public ModelJarRequirement build() {
      return new ModelJarRequirement(
          source,
          Optional.ofNullable(versionRange),
          Optional.ofNullable(variant),
          Optional.ofNullable(backend),
          Optional.ofNullable(capability));
    }
  }
}

