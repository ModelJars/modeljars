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

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Loads ModelJars descriptors from marker JAR resources on the classpath. */
public final class ClasspathModelJarRegistry extends InMemoryModelJarRegistry {
  /** Classpath location used by marker JARs to advertise model descriptors. */
  public static final String REGISTRY_RESOURCE = "META-INF/modeljars/registry.properties";

  private ClasspathModelJarRegistry(List<ModelJarDescriptor> descriptors) {
    super(descriptors);
  }

  /**
   * Loads every marker visible to the current thread context class loader.
   *
   * @return the combined classpath registry
   */
  public static ClasspathModelJarRegistry load() {
    return load(Thread.currentThread().getContextClassLoader());
  }

  /**
   * Loads every marker visible to the supplied class loader.
   *
   * @param classLoader class loader to inspect, or {@code null} to use this class's loader
   * @return the combined classpath registry
   */
  public static ClasspathModelJarRegistry load(ClassLoader classLoader) {
    ClassLoader loader =
        classLoader == null ? ClasspathModelJarRegistry.class.getClassLoader() : classLoader;
    Map<ModelJarCoordinate, ModelJarDescriptor> descriptors = new LinkedHashMap<>();

    try {
      Enumeration<URL> resources = loader.getResources(REGISTRY_RESOURCE);
      while (resources.hasMoreElements()) {
        URL resource = resources.nextElement();
        Properties properties = new Properties();
        try (InputStream input = resource.openStream()) {
          properties.load(input);
        }
        for (ModelJarDescriptor descriptor :
            PropertiesModelJarRegistry.fromProperties(properties).descriptors()) {
          descriptors.putIfAbsent(descriptor.markerCoordinate(), descriptor);
        }
      }
    } catch (IOException e) {
      throw new ModelJarException("Unable to load ModelJars registry resources", e);
    }

    return new ClasspathModelJarRegistry(List.copyOf(descriptors.values()));
  }
}
