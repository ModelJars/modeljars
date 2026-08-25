package org.modeljars.cli;

import java.util.Objects;
import java.util.regex.Pattern;

record ContributionSource(String repository) {
  private static final Pattern COMPONENT = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._-]{0,95})");

  static ContributionSource parse(String input) {
    String value = Objects.requireNonNull(input, "input").trim();
    if (value.startsWith("hf://")) {
      value = value.substring("hf://".length());
    } else if (value.startsWith("https://huggingface.co/")) {
      value = value.substring("https://huggingface.co/".length());
    } else if (value.contains("://")) {
      throw new IllegalArgumentException("Only Hugging Face repository sources are currently supported");
    }
    while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
    String[] parts = value.split("/", -1);
    if (parts.length != 2 || !COMPONENT.matcher(parts[0]).matches() || !COMPONENT.matcher(parts[1]).matches()) {
      throw new IllegalArgumentException("Expected a Hugging Face repository in OWNER/REPOSITORY form: " + input);
    }
    return new ContributionSource(parts[0] + '/' + parts[1]);
  }
}
