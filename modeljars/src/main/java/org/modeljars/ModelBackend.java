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

/**
 * Backend policy used when opening a qualified ModelJar.
 *
 * <p>{@link #AUTO} selects the backend recorded by the artifact's production qualification.
 */
public enum ModelBackend {
  /** Select the qualified backend automatically. */
  AUTO(null),

  /** Require the Java Vector API backend. */
  JAVA("pure-java"),

  /** Require the Models-owned Rust FFM kernel backend. */
  NATIVE("rust-ffm");

  private final String backendId;

  ModelBackend(String backendId) {
    this.backendId = backendId;
  }

  String backendId() {
    if (backendId == null) {
      throw new IllegalStateException("AUTO does not have a fixed backend ID");
    }
    return backendId;
  }
}
