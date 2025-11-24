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
import java.util.Objects;
import java.util.Set;

/**
 * A class that can parse out a small subset of a <a href="https://github.com/gradle/gradle/blob/v5.4.1/subprojects/docs/src/docs/design/gradle-module-metadata-1.0-specification.md">
 * Gradle Metadata File</a>.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MetadataFile {
    public Set<Variant> variants;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Variant {
        public String name;
        public Set<Dependency> dependencies;
        public Set<Dependency> dependencyConstraints;

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Variant variant)) {
                return false;
            }
            return Objects.equals(name, variant.name)
                    && Objects.equals(dependencies, variant.dependencies)
                    && Objects.equals(dependencyConstraints, variant.dependencyConstraints);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, dependencies, dependencyConstraints);
        }

        @Override
        public String toString() {
            return "Variant{name='" + name + "', dependencies=" + dependencies + ", dependencyConstraints="
                    + dependencyConstraints + '}';
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Dependency {
        public String group;
        public String module;
        public Map<String, String> version; // rich constraints

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Dependency that)) {
                return false;
            }
            return Objects.equals(group, that.group)
                    && Objects.equals(module, that.module)
                    && Objects.equals(version, that.version);
        }

        @Override
        public int hashCode() {
            return Objects.hash(group, module, version);
        }

        @Override
        public String toString() {
            return "Dependency{group='" + group + "', module='" + module + "', version=" + version + '}';
        }
    }
}
