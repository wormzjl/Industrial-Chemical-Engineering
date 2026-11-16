# Rules: Native code

The full rules of the `AGENTS.md` section "Native code", with the same force. `AGENTS.md` keeps the core and says when to read this file.

- The Java path is the reference and the fallback. A native path gives the same results as Java where its gate says so, and its ceiling states which solves it takes.
- A Java method with a Rust twin changes in both in the same batch, or the batch lowers the native ceiling below it.
- Run `cargo` from `src/main/rust` (its config sets 4 jobs, the target folder and the vendored sources). A cargo build or test uses at most 4 jobs, counts as one of the two Gradle lanes and never runs during a timed run. The merge lane builds the native library through Gradle.
