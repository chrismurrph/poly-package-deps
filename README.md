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

### 5. Distance (D) - "How far from the ideal?"

**Formula**: `D = |A + I - 1|` (standard JDepend formula)

Ranges from 0 (ideal) to 1 (worst).

The "main sequence" is the line where A + I = 1. Components on this line balance abstraction and stability appropriately:
- Stable components (low I) should be abstract (high A) to protect dependents
- Unstable components (high I) can be concrete (low A) since nothing depends on them

- **D = 0**: On the main sequence. Abstraction matches stability.
- **D = 1**: In a "zone" - either pain (concrete + stable) or uselessness (abstract + unstable).

---

## Understanding the Metrics

### What does Abstractness measure?

Unlike the original JDepend metric that counted all namespaces, **poly-metrics only considers namespaces accessed by other components** (not bases or projects).

- **A = 1.0**: All access from other components goes through interface namespaces.
- **A < 1.0**: Some components require your implementation namespaces directly.
- **A = 0.0**: All access bypasses your interface entirely.

Components with no other components depending on them get A=1.0 by default (nothing to leak).

### The Zones

**Zone of Pain** (D=1 when A=0, I=0): Concrete and stable. Many depend on this component, but it exposes implementation details. Hard to change safely.

**Zone of Uselessness** (D=1 when A=1, I=1): Abstract and unstable. Nothing depends on this, but it depends on others. In Polylith, these are often entry-point components used by bases/projects - not truly useless.

### Example: The Zone of Pain

```
antq: Ca=3, Ce=2, I=0.40, A=0.00, D=0.60
```

This is a problem because:
- 3 components depend on antq
- All access is to implementation namespaces (A=0)
- Those components are coupled to antq's internals

**The fix**: Route external access through interface namespaces.

### What about bases and projects?

**Bases** (like CLI runners) are entry points that wire components together. They're included in the dependency graph - if a base uses a component, that component's Ca (afferent coupling) increases. However, bases themselves aren't shown in the metrics table since they're always leaf nodes by design (nothing should depend on a base).

**Projects** are deployment configurations (deps.edn files), not code. They bundle components and bases for delivery but don't participate in the dependency graph.

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

## Design Decisions

### Why use the standard JDepend Distance formula?

We use `D = |A + I - 1|` rather than alternatives like `D = (1-A) * (1-I)` because:

1. **It's the established standard** - easier to compare with other tools and literature
2. **The "zone of uselessness" is informative** - components with D=1, A=1, I=1 aren't bugs; they're leaf components. The metric correctly identifies them as unusual, prompting investigation.

### Why are bases included in Ca but not shown in the report?

Early versions showed a "Bases" column listing which bases used each component. We removed it because:

1. **Bases are already in Ca** - the dependency graph includes bases, so a component used by 2 bases already has those counted in its afferent coupling
2. **Redundant information** - the DEPENDENTS section of component detail already lists bases alongside components
3. **Simpler report** - fewer columns, same information

### Why no "Projects" column?

We initially added a "Projects" column showing which projects included each component. We removed it because:

1. **Every component is in projects** - in a typical Polylith workspace, all components are bundled into deployment projects, so the column provided no discriminating information
2. **Projects aren't code dependencies** - they're packaging configurations, not part of the dependency graph

### Why only show components (not bases) in the metrics table?

Bases would always show D=1 (zone of uselessness) because:
- Nothing depends on a base (Ca=0) → I=1 (fully unstable)
- No code requires base namespaces externally → A=1 (fully abstract)
- Therefore D = |1 + 1 - 1| = 1

This is correct but not useful - bases are *designed* to be leaf nodes. Showing them would add noise without insight.

### Why is this tool Polylith-specific?

JDepend works on any Java codebase because Java has explicit constructs the metrics rely on:
- **Packages** are declared (`package com.foo.bar`)
- **Abstract types** are declared (`abstract class`, `interface`)

Clojure has neither. This tool works with Polylith because Polylith *creates* these constructs by convention:

1. **Brick boundaries** - Components and bases have explicit directories. We know `user.interface` belongs to the `user` component because it's in `components/user/`.

2. **Interface detection** - Polylith convention puts public API in `interface.clj`. We can distinguish "abstract" (interface namespaces) from "concrete" (implementation namespaces).

**Could this work for regular Clojure?**

Partially. Ca, Ce, Instability, and cycle detection would work - you'd just need to define what a "package" is:
- Each directory?
- Each top-level namespace prefix?
- User-configured groupings?

But **Abstractness becomes meaningless** without a way to distinguish interface from implementation. Options would be:
- A naming convention (e.g., `*_api.clj` files are interfaces)
- User configuration specifying which namespaces are interfaces
- Drop Abstractness entirely and just report Ca/Ce/I/cycles

The core value of this tool is the Abstractness metric - identifying leaky abstractions where other components bypass the interface. Without Polylith's conventions, you'd need to establish your own.

### Future: Support for regular Clojure projects?

Ca, Ce, Instability, and cycle detection are useful even without Abstractness. A future version could support regular Clojure projects by:

1. Detecting project type (Polylith vs regular deps.edn/lein project)
2. For regular projects, treating each source directory (or namespace prefix) as a "package"
3. Reporting only Ca/Ce/I/cycles (skipping A and D)
4. Showing "directory" or "package" in the Type column instead of "component"

This would provide coupling analysis and cycle detection for any Clojure codebase, with the full metrics available only for Polylith workspaces.

---

## References

- [JDepend](https://github.com/clarkware/jdepend) - Original Java tool
- [Robert Martin's Stability Metrics](https://www.objectmentor.com/resources/articles/stability.pdf) - The theory
- [Polylith](https://polylith.gitbook.io/) - The architecture this tool analyzes
