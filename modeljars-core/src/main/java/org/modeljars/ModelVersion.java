package org.modeljars;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small SemVer-compatible value object for model lineage versions. */
public final class ModelVersion implements Comparable<ModelVersion> {
  private static final Pattern PATTERN =
      Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z._-]+))?(?:\\+([0-9A-Za-z._-]+))?$");

  private final int major;
  private final int minor;
  private final int patch;
  private final String preRelease;
  private final String buildMetadata;
  private final String value;

  private ModelVersion(
      int major, int minor, int patch, String preRelease, String buildMetadata, String value) {
    this.major = major;
    this.minor = minor;
    this.patch = patch;
    this.preRelease = preRelease;
    this.buildMetadata = buildMetadata;
    this.value = value;
  }

  public static ModelVersion parse(String value) {
    if (value == null) {
      throw new IllegalArgumentException("version must not be null");
    }
    Matcher matcher = PATTERN.matcher(value.trim());
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Invalid model version: " + value);
    }
    return new ModelVersion(
        Integer.parseInt(matcher.group(1)),
        Integer.parseInt(matcher.group(2)),
        Integer.parseInt(matcher.group(3)),
        matcher.group(4),
        matcher.group(5),
        value.trim());
  }

  public String preRelease() {
    return preRelease;
  }

  public String buildMetadata() {
    return buildMetadata;
  }

  @Override
  public int compareTo(ModelVersion other) {
    int majorCompare = Integer.compare(major, other.major);
    if (majorCompare != 0) {
      return majorCompare;
    }
    int minorCompare = Integer.compare(minor, other.minor);
    if (minorCompare != 0) {
      return minorCompare;
    }
    int patchCompare = Integer.compare(patch, other.patch);
    if (patchCompare != 0) {
      return patchCompare;
    }
    return comparePreRelease(preRelease, other.preRelease);
  }

  private static int comparePreRelease(String left, String right) {
    if (Objects.equals(left, right)) {
      return 0;
    }
    if (left == null) {
      return 1;
    }
    if (right == null) {
      return -1;
    }

    List<String> leftParts = splitQualifier(left);
    List<String> rightParts = splitQualifier(right);
    int limit = Math.min(leftParts.size(), rightParts.size());
    for (int i = 0; i < limit; i++) {
      String l = leftParts.get(i);
      String r = rightParts.get(i);
      boolean leftNumeric = l.chars().allMatch(Character::isDigit);
      boolean rightNumeric = r.chars().allMatch(Character::isDigit);
      int compared;
      if (leftNumeric && rightNumeric) {
        compared = Integer.compare(Integer.parseInt(l), Integer.parseInt(r));
      } else if (leftNumeric) {
        compared = -1;
      } else if (rightNumeric) {
        compared = 1;
      } else {
        compared = l.compareTo(r);
      }
      if (compared != 0) {
        return compared;
      }
    }
    return Integer.compare(leftParts.size(), rightParts.size());
  }

  private static List<String> splitQualifier(String qualifier) {
    String[] parts = qualifier.split("[._-]");
    List<String> result = new ArrayList<>(parts.length);
    for (String part : parts) {
      if (!part.isBlank()) {
        result.add(part);
      }
    }
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof ModelVersion other && value.equals(other.value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  @Override
  public String toString() {
    return value;
  }
}

