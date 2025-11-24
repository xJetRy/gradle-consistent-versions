/*
 * (c) Copyright 2019 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.gradle.versions;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;
import java.util.Set;

/**
 * A class that can parse out a small subset of a <a href="https://github.com/gradle/gradle/blob/v5.4.1/subprojects/docs/src/docs/design/gradle-module-metadata-1.0-specification.md">
 * Gradle Metadata File</a>.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record MetadataFile(Set<Variant> variants) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Variant(String name, Set<Dependency> dependencies, Set<Dependency> dependencyConstraints) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Dependency(String group, String module, Map<String, String> version) {
        // Constructor with individual parameters for easier construction in tests
        public Dependency(String group, String module, Map<String, String> version) {
            this.group = group;
            this.module = module;
            this.version = version;
        }
    }
}
