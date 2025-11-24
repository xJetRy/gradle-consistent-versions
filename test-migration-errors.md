# Test Migration Errors and Fixes

## First Compilation Attempt - Errors Found

### Error 1: BuildGradle import not found
**Problem**: Tried to import `com.palantir.gradle.testing.project.BuildGradle` which doesn't exist.
**Fix**: The `buildGradle()` method returns `GradleFile`, not `BuildGradle`. Changed return type of `setupBuildFile()` to `GradleFile` and removed the import.

### Error 2: MetadataFile.Dependency and MetadataFile.Variant not accessible
**Problem**: Cannot use `MetadataFile.Dependency` and `MetadataFile.Variant` classes which are inner classes of a Groovy class.
**Fix**: Need to convert MetadataFile from Groovy to Java to make it accessible, or keep it as Groovy and access it properly. The Groovy classes should be accessible from Java, but need proper handling.

### Error 3: assertThat(String) not available from GradlePluginTestAssertions
**Problem**: Used `assertThat(String)` from `GradlePluginTestAssertions` which only provides `assertThat(InvocationResult)` and `assertThat(TaskOutcome)`.
**Fix**: For String assertions, use AssertJ's `org.assertj.core.api.Assertions.assertThat()` instead. Updated import to include both:
- `import static com.palantir.gradle.testing.assertion.GradlePluginTestAssertions.assertThat;` for Gradle-specific assertions
- `import static org.assertj.core.api.Assertions.assertThat;` for String and general assertions

### Error 4: SubProject.directory() requires String parameter
**Problem**: Called `foo.directory().toFile()` but `directory(String path)` requires a String parameter.
**Fix**: SubProject has a `path()` method that returns the Path. Should use `foo.path().toFile()` instead. However, the verifyLockfile method expects a File, so changed to use `foo.path()` and convert in the method.

### Error 5: File vs Path usage
**Problem**: Mixed usage of File and Path APIs throughout the code.
**Fix**: The new framework uses Path consistently. Updated verifyLockfile to work with Path instead of File. Also updated the signature from `File projectDir` to `Path projectDir`.

### Error 6: Groovy MetadataFile not accessible from Java
**Problem**: The MetadataFile class was written in Groovy and Java couldn't access its inner classes properly.
**Fix**: Created a new Java version of MetadataFile.java with proper inner classes (Dependency and Variant) that Java can access. Used Jackson annotations for JSON parsing.

### Error 7: Error Prone RuntimeException checks
**Problem**: Error Prone check [PreferUncheckedIoException] triggered for `throw new RuntimeException("...", IOException)`
**Fix**: Changed all `RuntimeException` wrapping `IOException` to `java.io.UncheckedIOException`.

### Error 8: Error Prone equals() getClass check
**Problem**: Error Prone check [EqualsGetClass] warns about using `getClass()` in equals methods.
**Fix**: Changed from `if (o == null || getClass() != o.getClass())` to `if (!(o instanceof ClassName))` in both Variant and Dependency classes.

### Error 9: Error Prone pattern matching instanceof
**Problem**: Error Prone check [PatternMatchingInstanceof] suggests using pattern-matching instanceof.
**Fix**: Changed from:
```java
if (!(o instanceof Variant)) {
    return false;
}
Variant variant = (Variant) o;
```
To:
```java
if (!(o instanceof Variant variant)) {
    return false;
}
```

### Error 10: GradleTestPluginsBlock Error Prone check
**Problem**: Error Prone check [GradleTestPluginsBlock] triggered for any `apply plugin:` or `plugins.apply()` in append() calls.
**Fix**: Must use the `.plugins().add("plugin-id")` API before calling `.append()` with configuration. This involved:
- Extracting all `apply plugin: 'xxx'` lines from append() calls
- Calling `buildGradle().plugins().add("xxx")` before the append
- Removing the plugin application from the appended string
- For plugins in `subprojects {}` blocks, applied plugins directly to each subproject instead of using `plugins.apply()` in the block

### Error 11: Unused variable warnings
**Problem**: Error Prone check [StrictUnusedVariable] for unused parameters like `rootProject`.
**Fix**: Removed unused parameters from method signatures. The framework's parameter injection only provides what's needed.

## Second Pass - Review Against Testing Guide

After compilation succeeded, reviewed the migrated test against the testing-guide.md to ensure best practices:

1. ✅ Used `@GradlePluginTests` annotation
2. ✅ Used parameter injection for GradleInvoker, RootProject, SubProject, MavenRepo
3. ✅ Used `.plugins().add()` API for adding plugins
4. ✅ Used `.buildGradle()`, `.settingsGradle()`, `.gradlePropertiesFile()` APIs
5. ✅ Used MavenRepo.publish() with MavenArtifact.of() and MavenArtifact.builder()
6. ✅ Used proper assertions: `assertThat(result)` from GradlePluginTestAssertions for Gradle results, and `assertThat(string)` from AssertJ for String assertions
7. ✅ Used `.buildsSuccessfully()` and `.buildsWithFailure()` instead of `.build()` and `.buildAndFail()`
8. ✅ Used text blocks for multi-line strings
9. ✅ Used Path instead of File throughout
10. ✅ Kept delineator comments from original test for review purposes

## Key Learnings

1. **Two assertThat imports needed**: The framework provides `assertThat()` for `InvocationResult` and `TaskOutcome`, but AssertJ's `assertThat()` is needed for String and other types.

2. **Plugins API is mandatory**: The Error Prone check enforces using `.plugins().add()` and won't allow `apply plugin:` in append() calls.

3. **Path over File**: The new framework uses java.nio.file.Path consistently instead of java.io.File.

4. **SubProject access**: SubProjects have a `path()` method that returns their Path directly, no need for `directory()` method without parameters.

5. **MavenRepo setup**: The MavenRepo must be set up in @BeforeEach and can publish artifacts with dependencies using the builder pattern.

6. **Helper methods for build file setup**: Created a `setupBuildFile()` helper method that returns `GradleFile` to encapsulate common build file configuration.

7. **Groovy to Java conversion**: When Groovy classes are used by Java tests, they may need to be converted to Java for proper accessibility of inner classes.

## Test Migration Complete

The test has been successfully migrated from Nebula/Spock/Groovy to the new Java-based testing framework. All compilation errors have been resolved and the test follows the framework's best practices.