package org.modeljars.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SystemCapabilitiesTest {
  @Test
  void parsesAppleGraphicsInventory() {
    List<SystemCapabilities.GraphicsDevice> devices =
        SystemCapabilities.parseMacGraphics(
            """
            Graphics/Displays:
                Apple M2 Max:
                  Chipset Model: Apple M2 Max
                  Type: GPU
            """);

    assertEquals(1, devices.size());
    assertEquals("Apple M2 Max", devices.getFirst().name());
    assertTrue(devices.getFirst().dedicatedMemoryBytes().isEmpty());
  }

  @Test
  void parsesNvidiaGraphicsMemoryWithoutClaimingBackendSupport() {
    List<SystemCapabilities.GraphicsDevice> devices =
        SystemCapabilities.parseNvidiaGraphics("NVIDIA L4, 23034\nNVIDIA T4, 15360\n");

    assertEquals(2, devices.size());
    assertEquals("NVIDIA L4", devices.getFirst().name());
    assertEquals(23034L * 1024L * 1024L, devices.getFirst().dedicatedMemoryBytes().orElseThrow());
  }

  @Test
  void parsesPciAndWindowsGraphicsFallbacks() {
    assertEquals(
        "NVIDIA Corporation AD104GL [L4]",
        SystemCapabilities.parsePciGraphics(
                "01:00.0 VGA compatible controller: NVIDIA Corporation AD104GL [L4]\n")
            .getFirst()
            .name());
    assertEquals(
        8_589_934_592L,
        SystemCapabilities.parseWindowsGraphics("NVIDIA GeForce RTX 4070\t8589934592\n")
            .getFirst()
            .dedicatedMemoryBytes()
            .orElseThrow());
  }
}
