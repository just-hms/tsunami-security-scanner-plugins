/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.tsunami.plugins.detectors.directorytraversal.genericpathtraversaldetector;

import com.google.common.collect.ImmutableSet;
import com.google.tsunami.common.net.http.HttpRequest;
import com.google.tsunami.proto.NetworkService;

/**
 * An {@code InjectionPoint} defines a sink in an HTTP request that can be instantiated with a
 * payload.
 */
interface InjectionPoint {
  /**
   * Return all requests that can be generated by injecting a {@code payload} in the context of the
   * sink.
   */
  ImmutableSet<PotentialExploit> injectPayload(
      NetworkService networkService, HttpRequest request, String payload);
}