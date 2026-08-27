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

/** Element precision used when estimating key/value cache storage. */
public enum KvCachePrecision {
  /** 32-bit floating-point key/value elements. */
  FLOAT32(4),

  /** 16-bit floating-point key/value elements. */
  FLOAT16(2),

  /** 8-bit integer key/value elements. */
  INT8(1);

  private final int bytesPerElement;

  KvCachePrecision(int bytesPerElement) {
    this.bytesPerElement = bytesPerElement;
  }

  /**
   * Returns the storage required by one cache element.
   *
   * @return bytes per element
   */
  public int bytesPerElement() {
    return bytesPerElement;
  }
}
