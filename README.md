# poly-metrics

JDepend-style maintainability metrics for Polylith Clojure workspaces.

## Quick Start

```bash
# Full report for a workspace
clj -M:run /path/to/polylith-workspace

# Details for a specific component
clj -M:run /path/to/polylith-workspace component util

# Output as JSON
clj -M:run /path/to/polylith-workspace json

# Help
clj -M:run --help
```

## The Five Metrics

When you run `clj -M:run /path/to/workspace component util`, you'll see five metrics:

### 1. Afferent Coupling (Ca) - "Who uses me?"

The number of other components that depend on this one.

- **High Ca**: Many components use this one. It's a foundational piece.
- **Low Ca**: Few or no components use this. It's a leaf or isolated component.

Example: `util` has Ca=29, meaning 29 other components depend on it.

### 2. Efferent Coupling (Ce) - "Who do I use?"

The number of other components this one depends on.

- **High Ce**: This component depends on many others. It's high in the dependency tree.
- **Low Ce**: This component is self-contained or foundational.

Example: `util` has Ce=0, meaning it doesn't depend on any other components.

### 3. Instability (I) - "How risky is change?"

**Formula**: `I = Ce / (Ca + Ce)`

Ranges from 0 (stable) to 1 (unstable).

- **I ≈ 0 (Stable)**: Many depend on this, it depends on little. Changes here ripple outward and could break many things. Be careful.
- **I ≈ 1 (Unstable)**: Nothing depends on this, but it depends on others. Changes here are low-risk - you're only affected by others, not affecting them.

Example: `util` has I=0.00 (maximally stable). If you change util, you could break 29 components. Think twice.

### 4. Abstractness (A) - "How clean is the interface?"

**Formula**: `A = interface-ns-accessed-externally / total-ns-accessed-externally`

Ranges from 0 (leaky abstraction) to 1 (clean interface).

This metric only considers namespaces that are **actually required by other components**. Internal namespaces that aren't used externally don't count.

- **A = 1.0 (Perfect)**: All external access goes through interface namespaces. Clean API.
- **A = 0.0 (Leaky)**: All external access is to implementation namespaces. Other components are coupled to your internals.

Example: `util` has A=1.0, meaning all 29 components that depend on it use its interface namespaces, not its implementation namespaces directly.

### 5. Distance (D) - "How balanced is this component?"

**Formula**: `D = |A + I - 1|`

Ranges from 0 (ideal) to 1 (worst).

This is the key metric. It measures deviation from the "Main Sequence" - the ideal line where `A + I = 1`.

- **D ≈ 0**: The component's abstraction level matches its stability. Well-balanced.
- **D > 0.5**: Something is off. The component may be hard to maintain.

---

## Understanding Abstractness and Distance

### What does Abstractness really measure?

Unlike the original JDepend metric that counted all namespaces, **poly-metrics only considers externally-visible namespaces** - those actually required by other components.

This makes Abstractness a measure of **API cleanliness**:

- **A = 1.0**: Other components only use your interface namespaces. Your implementation is encapsulated.
- **A < 1.0**: Some external code requires your implementation namespaces directly. This is a "leaky abstraction."
- **A = 0.0**: All external access bypasses your interface entirely.

A component with no external dependencies gets A=1.0 by default (nothing to leak).

### Why does this matter?

When external code requires your implementation namespaces:
- They're coupled to your internal details
- Changing your implementation might break them
- The interface isn't doing its job of hiding internals

### The "Main Sequence" and Distance

The **Main Sequence** is the line where `A + I = 1`:

```
        1.0 +
            |  Zone of        .
Abstractness|  Uselessness  .
            |             .
        0.5 +           .    <- Main Sequence (A + I = 1)
            |         .          Ideal components live here
            |       .
            |     .   Zone of
        0.0 +   .     Pain
            +---+---+---+---+
           0.0     0.5     1.0
               Instability
```

With the new abstractness calculation:

- **Perfect components (A=1.0)** have D = |1.0 + I - 1| = I
- **Stable with clean interface** (I=0, A=1.0): D=0 - ideal!
- **Unstable with clean interface** (I=1, A=1.0): D=1 - abstract but unused
- **Stable with leaky interface** (I=0, A=0): D=1 - zone of pain

### When Distance matters

**Leaky stable components** are the real problem:

```
antq: Ca=3, Ce=2, I=0.40, A=0.00, D=0.60
```

This means:
- 3 components depend on antq
- All external access is to implementation namespaces (A=0)
- Other components are coupled to antq's internals

**The fix**: Route external access through interface namespaces.

### When Distance doesn't matter

**Leaf components** (high instability, nothing depends on them) with high distance aren't concerning - there's nothing to leak to since no one uses them.

---

## Practical Guidelines for Clojure/Polylith

1. **Focus on Abstractness (A)** - This tells you if other components are bypassing your interface. A < 1.0 means there's leakage.

2. **Stable components with low A are the problem** - If many depend on you (low I) and they're requiring your implementation namespaces (low A), you have a maintenance risk.

3. **Cycles are always bad** - Unlike Distance, cyclic dependencies are a real problem in any paradigm.

4. **Leaf components (high I) don't matter much** - If nothing depends on you, there's nothing to leak to.

5. **A = 1.0 is the goal for any component others depend on** - Route all external access through interface namespaces.

---

## Metrics Interpretation Summary

| Metric | Good | Concerning | What to do |
|--------|------|------------|------------|
| Ca | Any | - | Just informational |
| Ce | Any | Very high (> 15?) | Consider if component does too much |
| I | Any | - | Just informational |
| A | 1.0 | < 1.0 (especially if stable) | Route external access through interface |
| D | Low | High for stable components | Fix abstractness |
| Cycles | 0 | Any | Break the cycle |

---

## Exit Codes

- `0` - Healthy (mean distance < 0.5, no cycles)
- `1` - Needs attention
- `2` - Error (not a Polylith workspace, component not found)

---

## References

- [JDepend](https://github.com/clarkware/jdepend) - Original Java tool
- [Robert Martin's Stability Metrics](https://www.objectmentor.com/resources/articles/stability.pdf) - The theory
- [Polylith](https://polylith.gitbook.io/) - The architecture this tool analyzes
