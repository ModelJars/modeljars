package org.modeljars;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ModelJarInstallerCliTest {
  @Test
  void rejectsMissingSource() {
    assertThrows(IllegalArgumentException.class, () -> ModelJarInstallerCli.main(new String[0]));
  }

  @Test
  void rejectsUnknownOptionsBeforeResolving() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelJarInstallerCli.main(new String[] {"hf://example/model", "--unknown", "x"}));
  }

  @Test
  void rejectsOptionsWithoutValues() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ModelJarInstallerCli.main(new String[] {"hf://example/model", "--variant"}));
  }
}
