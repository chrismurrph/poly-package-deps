# poly-metrics Project Summary

JDepend-style maintainability metrics for Polylith Clojure workspaces.

## Terminology

DAG terminology (NOT tree terminology):
- **Entry point / source node**: Ca=0, nothing depends on it - use "entry point"
- **Dead end / sink node**: Ce=0, depends on nothing - use "dead end"
- Do NOT use "leaf" or "leaves" - in a tree it has one meaning (no children), but in a DAG it's ambiguous: could mean no outgoing edges (Ce=0) or no incoming edges (Ca=0). Use "entry point" or "dead end" instead.
- Do NOT use "foundation", "foundations", or "foundational" - vague and not DAG terminology. Use "dead end" (Ce=0, depends on nothing) or describe the property directly (e.g., "high Ca", "many depend on it").

## Key Metrics

- **Ca (Afferent Coupling)**: Who depends on me?
- **Ce (Efferent Coupling)**: Who do I depend on?
- **I (Instability)**: Ce / (Ca + Ce) - 0=stable, 1=unstable
- **A (Abstractness)**: interface-ns / total-ns externally accessed - 0=leaky, 1=clean
- **D (Distance)**: |A + I - 1| - distance from main sequence

## Project Structure

```
src/poly_metrics/
  core.clj       - CLI entry point
  discovery.clj  - Bottom-up package discovery and classification
  graph.clj      - Dependency graph building and analysis
  metrics.clj    - JDepend metrics calculation
  report.clj     - Output formatting (text, EDN, JSON)
  workspace.clj  - Polylith workspace utilities
  parse.clj      - Clojure file parsing
```

## Supported Project Types

- **Polylith**: `components/`, `bases/` directories
- **Polylith-like**: `interfaces/`, `packages/` directories
- **Plain Clojure**: Any directory with .clj files (partial metrics only)

## Running

```bash
clj -M:run /path/to/project           # Full report
clj -M:run /path/to/project json      # JSON output
clj -M:run /path/to/project package X # Details for package X
```
