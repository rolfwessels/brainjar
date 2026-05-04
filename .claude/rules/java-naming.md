---
description: Java coding conventions and language preferences
globs:
  - "**/*.java"
---

# Java Conventions

## General Preferences

- Use Java records for DTOs
- Use `var` only when the type is already visible on the right side (`var list = new ArrayList<>()`, `var order = new Order(...)`). Prefer explicit types when the type comes from a method call
- Use constructor injection — Spring auto-wires single-constructor beans
- All injected dependencies must be `final`
- **Never use field injection** (`@Autowired` or `@Value` on fields)
- Use `Optional` for nullable return types from lookup methods
- Use `List.of()`, `Map.of()` for immutable collections
- Use `.toList()` on streams (returns unmodifiable list) instead of `.collect(Collectors.toList())`
- Use `String.formatted()` for string formatting
- Prefer readable lambdas over method references when the lambda is clearer (`.filter(a -> a != null)` over `.filter(Objects::nonNull)`)

## Java 21 Features

Use these modern features — all stable in Java 21:

**Record compact constructors** — enforce immutability on collection fields:
```java
public record Movie(String id, String title, List<String> actorIds) {
    public Movie {
        actorIds = List.copyOf(actorIds);
    }
}
```

**Sealed interfaces** — use when you have a closed set of subtypes:
```java
sealed interface ApiResponse<T> permits Success, Failure {}
record Success<T>(T data) implements ApiResponse<T> {}
record Failure<T>(String code, String message) implements ApiResponse<T> {}
```

**Pattern matching for switch** — replace if/else chains and manual casting:
```java
String describe(Object obj) {
    return switch (obj) {
        case Integer i when i > 0 -> "positive";
        case Integer i -> "non-positive";
        case String s when s.isBlank() -> "blank";
        case String s -> s;
        case null -> "null";
        default -> obj.getClass().getSimpleName();
    };
}
```

**Pattern matching for instanceof** — eliminate manual casting:
```java
// BAD
if (obj instanceof String) { String s = (String) obj; process(s); }

// GOOD
if (obj instanceof String s) { process(s); }
```

**Record patterns** — destructure records in instanceof and switch:
```java
if (event instanceof OrderEvent(var orderId, var status)) {
    log.info("Order {} is {}", orderId, status);
}
```

**Text blocks** — for multi-line strings (SQL, JSON, GraphQL):
```java
var query = """
        { movies { id title year } }
        """;
```

**Sequenced collections** — use `getFirst()`, `getLast()`, `reversed()`:
```java
var first = movies.getFirst();
var reversed = movies.reversed();
```

## Method Extraction Pattern

When methods get complex, extract sections into smaller, well-named helpers:

```java
// BAD — one long method
public void processOrder(String orderId) {
    var order = repository.findById(orderId)
            .orElseThrow(() -> new NotFoundException("Order not found"));
    var subtotal = order.items().stream().mapToDouble(i -> i.price() * i.quantity()).sum();
    var total = subtotal + subtotal * 0.2;
    emailService.send(order.customerEmail(), "Order Confirmation",
            "Your order %s total is %.2f".formatted(orderId, total));
}

// GOOD — composed from named helpers
public void processOrder(String orderId) {
    var order = validateAndGetOrder(orderId);
    var total = calculateOrderTotal(order);
    sendConfirmationEmail(order, total);
}
```

## DRY: Don't Repeat Yourself

When two methods are nearly identical (differing only in a parameter), extract shared logic:

```java
// BAD — duplicated logic
public String getProductModelJsonSchema(String modelKey, String templateKey) { ... }
public String getProductItemJsonSchema(String modelKey, String templateKey) { ... }

// GOOD — shared helper
public String getProductModelJsonSchema(String modelKey, String templateKey) {
    return getSchemaByLevel(modelKey, templateKey, DataLevel.MODEL);
}
public String getProductItemJsonSchema(String modelKey, String templateKey) {
    return getSchemaByLevel(modelKey, templateKey, DataLevel.ITEM);
}
private String getSchemaByLevel(String modelKey, String templateKey, DataLevel level) { ... }
```
