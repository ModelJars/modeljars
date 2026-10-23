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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.integrallis.models.api.catalog.DiscoveredModel;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Reporting <em>where</em> an installed ModelJar is, not just that it is installed.
 *
 * <p>What is installed differs between machines, so these assert the invariants that must hold
 * whatever is present. The important one is that {@code discover} and {@code locate} describe the
 * same set: a model the catalog advertises and cannot locate would look like a defect in whichever
 * caller hit it, and one it can locate but never advertises would be invisible to the router while
 * still being openable by id.
 *
 * <p>Two of these would pass vacuously on a machine with nothing downloaded, so each says so rather
 * than reporting an empty loop as a success.
 */
class ModelJarsCatalogLocateTest {

  @Test
  void locatesEveryModelItAdvertises() {
    ModelJarsCatalog catalog = new ModelJarsCatalog();
    List<DiscoveredModel> discovered = catalog.discover();

    List<String> unlocatable = new ArrayList<>();
    for (DiscoveredModel model : discovered) {
      if (catalog.locate(model.id()).isEmpty()) {
        unlocatable.add(model.id());
      }
    }

    assertTrue(
        unlocatable.isEmpty(), "discover() advertises models locate() cannot find: " + unlocatable);
    // Said out loud, because an empty catalogue would make the assertion above meaningless.
    System.out.println(
        "locatesEveryModelItAdvertises checked " + discovered.size() + " installed model(s)");
  }

  @Test
  void returnsAPathThatReallyExists() {
    ModelJarsCatalog catalog = new ModelJarsCatalog();
    int checked = 0;
    for (DiscoveredModel model : catalog.discover()) {
      Path located = catalog.locate(model.id()).orElseThrow();
      assertTrue(
          Files.exists(located), model.id() + " located at a path that does not exist: " + located);
      checked++;
    }
    System.out.println("returnsAPathThatReallyExists checked " + checked + " installed model(s)");
  }

  @Test
  void returnsTheSamePathModelJarsItselfWouldOpen() {
    // The consistency claim in locate's javadoc, asserted. If these diverged, this catalog would
    // send a caller to one file while the library's own loader used another.
    ModelJarRegistry registry = ModelJarRegistry.fromClasspath();
    ModelJarsCatalog catalog = new ModelJarsCatalog();

    int checked = 0;
    for (ModelJarDescriptor descriptor : registry.descriptors()) {
      Optional<Path> located = catalog.locate(descriptor.alias());
      if (located.isEmpty() || descriptor.localPath().filter(Files::isRegularFile).isPresent()) {
        // A marker with a configured localPath is loaded from that path by
        // ModelJarLocator.requireLocalPath, so it is not the cache path that applies to it.
        continue;
      }
      assertEquals(
          ModelJarCache.artifactPath(descriptor),
          located.orElseThrow(),
          descriptor.alias() + " is located somewhere other than where ModelJars would open it");
      checked++;
    }
    System.out.println(
        "returnsTheSamePathModelJarsItselfWouldOpen checked " + checked + " cached model(s)");
  }

  @Test
  void reportsNothingForACataloguedModelThatIsNotOnThisMachine() {
    // The catalogue is far larger than any one machine's cache, so this is the non-vacuous half of
    // the contract: not-installed has to be empty rather than a path that would fail to open.
    ModelJarRegistry registry = ModelJarRegistry.fromClasspath();
    ModelJarsCatalog catalog = new ModelJarsCatalog();

    List<String> installedIds = catalog.discover().stream().map(DiscoveredModel::id).toList();
    int checked = 0;
    for (ModelJarDescriptor descriptor : registry.descriptors()) {
      if (installedIds.contains(descriptor.alias())) {
        continue;
      }
      if (descriptor.localPath().filter(Files::isRegularFile).isPresent()) {
        // Installed by configuration rather than by the cache; discover() filters it out for
        // routing reasons (capability or context length), not for availability.
        continue;
      }
      Path artifact;
      try {
        artifact = ModelJarCache.artifactPath(descriptor);
      } catch (RuntimeException e) {
        // No digest, so not content-addressable: locate must still be empty, which is asserted
        // below without needing the path.
        assertTrue(catalog.locate(descriptor.alias()).isEmpty(), descriptor.alias());
        checked++;
        continue;
      }
      if (ModelJarCache.isComplete(descriptor, artifact)) {
        continue;
      }
      assertTrue(
          catalog.locate(descriptor.alias()).isEmpty(),
          descriptor.alias() + " is not installed yet locate() returned a path");
      checked++;
    }
    assertTrue(checked > 0, "no uninstalled catalogued model to check; this test proved nothing");
    System.out.println(
        "reportsNothingForACataloguedModelThatIsNotOnThisMachine checked " + checked + " model(s)");
  }

  @Test
  void bindsAsTheCatalogSpiMethodAsSoonAsTheInterfaceDeclaresOne() {
    // The guard for the missing @Override. This module builds against the published models
    // release, which has no `locate` on the SPI, so the annotation cannot be used yet -- and
    // without it a signature drift would silently stop overriding and nothing would notice.
    //
    // From the release that adds the method onwards this asserts the binding; before it, it says
    // plainly that it could not check rather than passing quietly.
    Method spiMethod;
    try {
      spiMethod =
          com.integrallis.models.api.catalog.ModelCatalogProvider.class.getMethod(
              "locate", String.class);
    } catch (NoSuchMethodException absent) {
      System.out.println(
          "ModelCatalogProvider has no locate(String) in this models release; "
              + "binding not checked. Restore @Override on ModelJarsCatalog.locate with the bump.");
      return;
    }

    Method implementation;
    try {
      implementation = ModelJarsCatalog.class.getMethod("locate", String.class);
    } catch (NoSuchMethodException e) {
      throw new AssertionError("ModelJarsCatalog.locate(String) is gone", e);
    }
    assertEquals(
        ModelJarsCatalog.class,
        implementation.getDeclaringClass(),
        "ModelJarsCatalog no longer overrides the SPI's locate; the signatures have drifted from "
            + spiMethod);
    assertFalse(
        implementation.isDefault(), "the SPI default is being used instead of this implementation");
  }

  @Test
  void reportsNothingForAnIdNoMarkerDeclares() {
    ModelJarsCatalog catalog = new ModelJarsCatalog();

    assertTrue(catalog.locate("no_such_model_id").isEmpty());
    assertTrue(catalog.locate("").isEmpty());
    assertTrue(catalog.locate("   ").isEmpty());
    assertTrue(catalog.locate(null).isEmpty());
  }

  @Test
  void neverReportsAPathForAFormatTheBackendsRefuse() {
    // ModelJars.cachePath refuses anything but gguf, cact and a safetensors marker with files, so
    // reporting one of those here would hand a caller a path the loader then rejects.
    ModelJarRegistry registry = ModelJarRegistry.fromClasspath();
    ModelJarsCatalog catalog = new ModelJarsCatalog();

    for (ModelJarDescriptor descriptor : registry.descriptors()) {
      boolean loadable =
          "gguf".equals(descriptor.format())
              || "cact".equals(descriptor.format())
              || ("safetensors".equals(descriptor.format()) && !descriptor.files().isEmpty());
      if (loadable) {
        continue;
      }
      assertFalse(
          catalog.locate(descriptor.alias()).isPresent(),
          descriptor.alias()
              + " has unloadable format "
              + descriptor.format()
              + " yet was located");
    }
  }
}
