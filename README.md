# poly-metrics

JDepend-style maintainability metrics for Polylith Clojure workspaces.

## Quick Start

```bash
# Full report for a workspace
clj -M:run /path/to/polylith-workspace

# Details for a specific package
clj -M:run /path/to/polylith-workspace package util

# Output as JSON
clj -M:run /path/to/polylith-workspace json

# Help
clj -M:run --help
```

## Terminology

This tool uses **package** as the generic term for a unit of code being analyzed:

- **Polylith**: A package is the Clojure namespaces that make up the implementation of a component
- **Polylith-like**: A package is a subdirectory under `packages/`, which again can have multiple namespaces

Throughout the documentation and output, "package" refers to whichever unit applies to your project structure.

## The Diagram

The metrics map each package onto a 2D space:

```
A=1        │
   \       │  Zone of
     \     │Uselessness
       \   │
         \ │
I=0────────\────────I=1
           │ \
           │   \
Zone of    │     \
 Pain      │       \
A=0     main sequence\
```

- **X-axis: Instability (I)** — 0 (stable, many depend on you) to 1 (unstable, free to change)
- **Y-axis: Abstractness (A)** — 0 (concrete, impl exposed) to 1 (abstract, clean interface)
- **Main sequence**: The diagonal where A + I = 1. Packages on this line are well-balanced.
- **Distance (D)**: How far from the main sequence. D=0 is ideal.

**Zone of Pain** (bottom-left, near I=0, A=0): Stable but concrete. Many depend on you, but you expose implementation details. Hard to change safely.

**Zone of Uselessness** (top-right, near I=1, A=1): Unstable but abstract. Clean interface, but nothing uses it. Why bother? Answer: Might be the API of a library - it gets used by other unknown code.

---

## The Five Metrics

When you run `clj -M:run /path/to/workspace package util`, you'll see five metrics:

### 1. Afferent Coupling (Ca) - "Who uses me?"

The number of other packages that depend on this one.

- **High Ca**: Many packages use this one. It has high responsibility.
- **Low Ca**: Few or no packages use this. It's an entry point or isolated package.

Example: `util` has Ca=29, meaning 29 other packages depend on it.

### 2. Efferent Coupling (Ce) - "Who do I use?"

The number of other packages this one depends on.

- **High Ce**: This package depends on many others. It's high in the dependency graph.
- **Low Ce**: This package is self-contained or a dead end (depends on nothing).

Example: `util` has Ce=0, meaning it doesn't depend on any other packages.

### 3. Instability (I) - "How risky is change?"

**Formula**: `I = Ce / (Ca + Ce)`

Ranges from 0 (stable) to 1 (unstable).

- **I ≈ 0 (Stable)**: Many depend on this, it depends on little. Changes here ripple outward and could break many things. Be careful.
- **I ≈ 1 (Unstable)**: Nothing depends on this, but it depends on others. Changes here are low-risk - you're only affected by others, not affecting them.

Example: `util` has I=0.00 (maximally stable). If you change util, you could break 29 packages. Think twice.

### 4. Abstractness (A) - "How clean is the interface?"

**Formula**: `A = interface-ns-accessed-externally / total-ns-accessed-externally`

Ranges from 0 (leaky abstraction) to 1 (clean interface).

This metric only considers namespaces that are **actually required by other packages**. Internal namespaces that aren't used externally don't count.

- **A = 1.0 (Perfect)**: All access by other packages goes through interface namespaces. Clean API.
- **A = 0.0 (Leaky)**: All access by other packages is to implementation namespaces. Other packages are coupled to your internals.

Example: `util` has A=1.0, meaning all 29 packages that depend on it use its interface namespace, not any implementation namespace directly.

In Polylith, each component typically has one interface namespace. So A < 1.0 means implementation namespaces are being accessed directly - the more impl namespaces accessed, the lower the A value.

### 5. Distance (D) - "How far from the ideal?"

**Formula**: `D = |A + I - 1|` (standard JDepend formula)

Ranges from 0 (ideal) to 1 (worst).

The "main sequence" is the line where A + I = 1. Packages on this line balance abstraction and stability appropriately:

- Stable packages (low I) should be abstract (high A) to protect dependents

- Unstable packages (high I) can be concrete (low A) since nothing depends on them

- **D = 0**: On the main sequence. Abstraction matches stability.

- **D = 1**: In a "zone" - either pain (concrete + stable) or uselessness (abstract + unstable).

---

## Understanding the Metrics

### What does Abstractness measure?

Unlike the original JDepend metric that counted all namespaces, **poly-metrics only considers namespaces accessed by other packages** (not bases or projects).

- **A = 1.0**: All access from other packages goes through interface namespaces.
- **A < 1.0**: Some packages require your implementation namespaces directly.
- **A = 0.0**: All access bypasses your interface entirely.

Packages with no other packages depending on them get A=1.0 by default (nothing to leak).

### The Zones

**Zone of Pain** (D=1 when A=0, I=0): Concrete and stable. Many depend on this package, but it exposes implementation details. Hard to change safely.

**Zone of Uselessness** (D=1 when A=1, I=1): Abstract and unstable. Nothing depends on this, but it depends on others. In Polylith, these are often entry-point components used by bases/projects - not truly useless.

### Example: The Zone of Pain

```
antq: Ca=3, Ce=2, I=0.40, A=0.00, D=0.60
```

This is a problem because:

- 3 packages depend on antq
- All access is to implementation namespaces (A=0)
- Those packages are coupled to antq's internals

**The fix**: Route external access through interface namespaces.

### What about bases and projects?

**Bases** (like CLI runners) are entry points that wire components together. They use components but don't count toward Ca (afferent coupling) - only other components count as internal dependents. Bases themselves are entry points by design (nothing should depend on a base).

**Projects** are deployment configurations (deps.edn files), not code. They bundle components and bases for delivery but don't participate in the dependency graph.

### Internal Dependents

**Internal dependents** are packages that depend on a given package, *excluding bases*.

- **Polylith**: Internal dependents = components that depend on this (bases excluded)
- **Polylith-like**: Internal dependents = all packages that depend on this (no bases exist)

Why exclude bases? Bases are external consumers - they're the boundary between your package system and the outside world. When measuring whether a package has a "leaky abstraction", we only care about other packages bypassing the interface. Bases are *supposed* to wire things together; they don't count as "internal" to the package system.

This affects two things:

1. **Afferent Coupling (Ca)**: Only counts internal dependents, not bases
2. **Entry Point Detection**: See below

### Entry Points

**Entry points** are packages consumed externally rather than by other internal packages.

- **Polylith component**: Entry point if Ca=0 (no internal dependents) AND used by at least one base
- **Polylith-like package**: Entry point if Ca=0 (no internal dependents)
- **Polylith base**: Always an entry point (by definition, nothing should depend on a base)

Why the difference? In Polylith, we can verify a component is actually consumed (by a base) rather than just dead code. In polylith-like, there are no bases, so we can only check that nothing internal depends on it.

Entry points are excluded from the distance calculation because abstractness is not meaningful when there are no internal dependents to protect from implementation details.

Note: Entry points are still components - they count as internal dependents when they depend on other components. Being an entry point doesn't make you "external" like a base.

---

## Practical Guidelines for Clojure/Polylith

1. **Focus on Abstractness (A)** - This tells you if other packages are bypassing your interface. A < 1.0 means there's leakage.

2. **Stable packages with low A are the problem** - If many depend on you (low I) and they're requiring your implementation namespaces (low A), you have a maintenance risk.

3. **Cycles are always bad** - Unlike Distance, cyclic dependencies are a real problem in any paradigm.

4. **Entry point packages (high I) don't matter much** - If nothing depends on you, there's nothing to leak to.

5. **A = 1.0 is the goal for any package others depend on** - Route all external access through interface namespaces.

---

## Metrics Interpretation Summary

| Metric | Good | Concerning                   | What to do                              |
| ------ | ---- | ---------------------------- | --------------------------------------- |
| Ca     | Any  | -                            | Just informational                      |
| Ce     | Any  | Very high (> 15?)            | Consider if package does too much       |
| I      | Any  | -                            | Just informational                      |
| A      | 1.0  | < 1.0 (especially if stable) | Route external access through interface |
| D      | Low  | High for stable packages     | Fix abstractness                        |
| Cycles | 0    | Any                          | Break the cycle                         |

---

## Exit Codes

- `0` - Healthy (mean distance < 0.5, no cycles)
- `1` - Needs attention
- `2` - Error (not a Polylith workspace, package not found)

---

## Design Decisions

### Why use the standard JDepend Distance formula?

We use `D = |A + I - 1|` rather than alternatives like `D = (1-A) * (1-I)` because:

1. **It's the established standard** - easier to compare with other tools and literature
2. **The "zone of uselessness" is informative** - packages with D=1, A=1, I=1 aren't bugs; they're entry point packages. The metric correctly identifies them as unusual, prompting investigation.

### Why are bases excluded from Ca?

Afferent coupling (Ca) only counts *internal dependents* - other packages that depend on this one. Bases are excluded because:

1. **Bases are external consumers** - they wire packages together for deployment, not for reuse within the package system
2. **Abstractness should measure internal coupling** - we want to know if *other packages* are bypassing the interface, not if bases are
3. **Bases always access packages** - including them would inflate Ca for packages used by multiple bases without providing insight

The DEPENDENTS section of package detail still lists bases alongside packages, so you can see full usage. But for metrics purposes, only internal dependents matter.

### Why no "Projects" column?

We initially added a "Projects" column showing which projects included each package. We removed it because:

1. **Every package is in projects** - in a typical Polylith workspace, all packages are bundled into deployment projects, so the column provided no discriminating information
2. **Projects aren't code dependencies** - they're packaging configurations, not part of the dependency graph

### Why only show packages (not bases) in the metrics table?

Bases would always show D=1 (zone of uselessness) because:

- Nothing depends on a base (Ca=0) → I=1 (fully unstable)
- No code requires base namespaces externally → A=1 (fully abstract)
- Therefore D = |1 + 1 - 1| = 1

This is correct but not useful - bases are *designed* to be entry points. Showing them would add noise without insight.

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

The core value of this tool is the Abstractness metric - identifying leaky abstractions where other packages bypass the interface. Without Polylith's conventions, you'd need to establish your own.

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
