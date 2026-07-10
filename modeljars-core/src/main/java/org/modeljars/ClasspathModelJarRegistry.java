package org.modeljars;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;

/** Loads ModelJars descriptors from marker JAR resources on the classpath. */
public final class ClasspathModelJarRegistry extends InMemoryModelJarRegistry {
  public static final String REGISTRY_RESOURCE = "META-INF/modeljars/registry.properties";

  private ClasspathModelJarRegistry(List<ModelJarDescriptor> descriptors) {
    super(descriptors);
  }

  public static ClasspathModelJarRegistry load() {
    return load(Thread.currentThread().getContextClassLoader());
  }

  public static ClasspathModelJarRegistry load(ClassLoader classLoader) {
    ClassLoader loader =
        classLoader == null ? ClasspathModelJarRegistry.class.getClassLoader() : classLoader;
    List<ModelJarDescriptor> descriptors = new ArrayList<>();

    try {
      Enumeration<URL> resources = loader.getResources(REGISTRY_RESOURCE);
      while (resources.hasMoreElements()) {
        URL resource = resources.nextElement();
        Properties properties = new Properties();
        try (InputStream input = resource.openStream()) {
          properties.load(input);
        }
        descriptors.addAll(PropertiesModelJarRegistry.fromProperties(properties).descriptors());
      }
    } catch (IOException e) {
      throw new ModelJarException("Unable to load ModelJars registry resources", e);
    }

    return new ClasspathModelJarRegistry(descriptors);
  }
}

