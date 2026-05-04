---
description: Java testing conventions and TDD practices
globs:
  - "**/*Test.java"
  - "**/*Tests.java"
  - "**/*IT.java"
---

# Java Testing Conventions

## TDD Principles

- Write tests for all new features
- If a test fails, fix the test only if the test is wrong — otherwise fix the code
- Never remove tests unless they are genuinely no longer needed
- Use Mockito for mocking — never use PowerMock
- When a service has an in-memory implementation available, use it instead of mocking

## Test Structure

- Use a `setup()` method to initialise test state — **never use `@BeforeEach`**
- Call `setup()` explicitly at the start of each test
- Test comments `// arrange`, `// act`, `// assert` are acceptable for clarity

```java
// GOOD
@Test
void createItem_WithValidInput_ShouldCreateItem() {
    // arrange
    setup();

    // act
    var result = service.createItem("key-1");

    // assert
    assertThat(result).isNotNull();
}

private void setup() {
    repository = new InMemoryRepository<>();
    service = new SampleService(repository);
}

// BAD
@BeforeEach
void setUp() {
    repository = new InMemoryRepository<>();
}
```

## What to Test

**Test your logic, not the framework.**

Test:
- Routing, transformation, validation, formatting, state management
- Config bindings (`@ConfigurationProperties` records) — pure Java, no mocks needed
- Service methods that take plain data and return plain data
- Command dispatch / routing maps

Do NOT test:
- Framework startup (`contextLoads` with no assertions)
- Thin adapters that only translate a framework event into a service call with no branching
- Mocking `void` chains of framework builder/action calls just to assert `.queue()` was called

If a class requires extensive framework mocking just to instantiate, that is a design signal — extract the real logic into a plain service or helper and test that instead.

## Sample Reuse Pattern

Create **one base sample method**, then create variations by calling the base:

```java
// ONE base sample
private static SampleModel createSampleModel(boolean isActive) {
    return new SampleModel(1L, "Sample Name", "test@example.com",
            Instant.now(), isActive,
            isActive ? Instant.now().minus(1, ChronoUnit.HOURS) : null);
}

private static SampleModel createSampleModel() {
    return createSampleModel(true);
}

// Variation reuses the base
private static SampleModel createInactiveModel() {
    return createSampleModel(false);
}
```

**Never duplicate object construction in variations:**
```java
// BAD — duplicates fields
private static SampleModel createInactiveModel() {
    return new SampleModel(2L, "Inactive", "inactive@example.com", Instant.now(), false, null);
}

// GOOD — reuses base
private static SampleModel createInactiveModel() {
    return createSampleModel(false);
}
```

## Running Tests

```bash
# All tests
./gradlew test

# Specific class
./gradlew test --tests "*.QueryResolverTest"

# Specific method
./gradlew test --tests "*.QueryResolverTest.movies_ShouldReturnSeededMovies"
```
