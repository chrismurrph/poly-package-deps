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

### 5. Distance (D) - "How problematic is this component?"

**Formula**: `D = (1 - A) * (1 - I)`

Ranges from 0 (ideal) to 1 (worst).

This formula penalizes components that are both **leaky** (low A) and **stable** (low I). These are the real maintenance risks - many components depend on them, and they're coupled to implementation details.

- **D = 0**: Either the interface is clean (A=1) or nothing depends on this component (I=1). No problem.
- **D > 0**: Some leakage in a component that others depend on. The higher D, the more critical it is to fix.

---

## Understanding the Metrics

### What does Abstractness measure?

Unlike the original JDepend metric that counted all namespaces, **poly-metrics only considers namespaces accessed by other components** (not bases or projects).

- **A = 1.0**: All access from other components goes through interface namespaces.
- **A < 1.0**: Some components require your implementation namespaces directly.
- **A = 0.0**: All access bypasses your interface entirely.

Components with no other components depending on them get A=1.0 by default (nothing to leak).

### Why the new Distance formula?

The original JDepend formula `D = |A + I - 1|` penalized unstable components even if they had clean interfaces. But in Polylith:

- Entry-level components (high I) naturally have nothing depending on them
- What matters is **leaky stable components** - implementation details that many depend on

The formula `D = (1 - A) * (1 - I)` only produces high distance when **both**:
- The abstraction is leaky (A < 1)
- The component is stable (I < 1) - meaning others depend on it

### The Zone of Pain

```
antq: Ca=3, Ce=2, I=0.40, A=0.00, D=0.60
```

This is a problem because:
- 3 components depend on antq
- All access is to implementation namespaces (A=0)
- Those components are coupled to antq's internals

**The fix**: Route external access through interface namespaces.

### What about bases?

Bases are entry points - their consumers are outside the system (CLI, web server, etc.). Since we can't see how they're accessed externally, we exclude them from metrics.

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
