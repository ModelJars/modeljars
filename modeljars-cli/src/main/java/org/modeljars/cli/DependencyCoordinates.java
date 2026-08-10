package org.modeljars.cli;

import java.util.Locale;
import org.modeljars.ModelJarCoordinate;

/** Dependency declarations for the build tools commonly used by JVM applications. */
final class DependencyCoordinates {
  enum Tool {
    MAVEN,
    GRADLE,
    GRADLE_KOTLIN,
    SBT,
    IVY,
    LEININGEN,
    JBANG;

    @Override
    public String toString() {
      return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
  }

  private DependencyCoordinates() {}

  static String render(ModelJarCoordinate coordinate, Tool tool) {
    return switch (tool) {
      case MAVEN -> maven(coordinate);
      case GRADLE -> "implementation '" + notation(coordinate) + "'";
      case GRADLE_KOTLIN -> "implementation(\"" + notation(coordinate) + "\")";
      case SBT ->
          "libraryDependencies += \""
              + coordinate.groupId()
              + "\" % \""
              + coordinate.artifactId()
              + "\" % \""
              + coordinate.version()
              + "\"";
      case IVY -> ivy(coordinate);
      case LEININGEN ->
          "["
              + coordinate.groupId()
              + '/'
              + coordinate.artifactId()
              + " \""
              + coordinate.version()
              + "\"]";
      case JBANG -> "//DEPS " + notation(coordinate);
    };
  }

  private static String maven(ModelJarCoordinate coordinate) {
    StringBuilder result =
        new StringBuilder()
            .append("<dependency>\n")
            .append("  <groupId>")
            .append(coordinate.groupId())
            .append("</groupId>\n")
            .append("  <artifactId>")
            .append(coordinate.artifactId())
            .append("</artifactId>\n")
            .append("  <version>")
            .append(coordinate.version())
            .append("</version>\n");
    coordinate.classifier().ifPresent(value -> result.append("  <classifier>").append(value).append("</classifier>\n"));
    if (!coordinate.type().equals("jar")) {
      result.append("  <type>").append(coordinate.type()).append("</type>\n");
    }
    return result.append("</dependency>").toString();
  }

  private static String ivy(ModelJarCoordinate coordinate) {
    StringBuilder result =
        new StringBuilder()
            .append("<dependency org=\"")
            .append(coordinate.groupId())
            .append("\" name=\"")
            .append(coordinate.artifactId())
            .append("\" rev=\"")
            .append(coordinate.version())
            .append('"');
    coordinate.classifier().ifPresent(value -> result.append(" conf=\"").append(value).append('"'));
    return result.append(" />").toString();
  }

  private static String notation(ModelJarCoordinate coordinate) {
    StringBuilder result =
        new StringBuilder()
            .append(coordinate.groupId())
            .append(':')
            .append(coordinate.artifactId())
            .append(':')
            .append(coordinate.version());
    coordinate.classifier().ifPresent(value -> result.append(':').append(value));
    if (!coordinate.type().equals("jar")) {
      result.append('@').append(coordinate.type());
    }
    return result.toString();
  }
}
