# H-R11 The certificate's graph digest becomes a declared replay revision: report

Batch H-R11 of `WAVE_D_BRIEFS.md` (subagent, Opus high). Worktree branch `worktree-agent-a6db650f168a51641`, fast-forwarded to `claude/codebase-review-refactor-8a85ef` at `fe6731f76`. **Nothing was compiled or run** (no Gradle, no JVM). Checks: node scripts in `tmp/hr11/` (gitignored).

Commits: `0e885476e` (code and tests), then this report with the code guide and `FORMATS.md`.

## 1. Step 1: where a `Signature` is built, compared, saved and read

| Place | What it does with the graph digest | Can the digest differ for a reason other than a code change? |
|---|---|---|
| `Certificates.certify` (line 109) | Builds the signature once at issue, from the certified interval's own result graph | No comparison here |
| `Certificates.restore` (line 124) | Keeps the saved signature on a restored certificate | No comparison |
| `IslandCertificate.restore` (line 469 before) | Compares the saved signature with `Signature.of(model, policy, saved.interval().result().graph())`: the digest of the **saved interval's own decoded graph** | Only when the codec does not give back what it wrote, or the digest's code changed. Both are code properties |
| `IslandCoordinator.Certified.equals` (`identity`, line 94) | Compares saved forms by interval ticks and signature | For one certificate the signature is fixed at issue. A topology change, a resume or a partner wake sets the certificate and signature to null (`IslandCoordinator` line 583, `ExchangerCoupling` line 234, `Certificates.revalidate`), so a new certificate has new interval ticks |
| `FluidCheckpointCodec` (lines 370, 607 before) | Writes the digest as 32 raw bytes after the two string references, and reads them back | Content only |
| Tests and `tools/fluid-probes/f3/probes/F3Fixtures.java` | Build signatures with `Signature.of(model, policy, graph)` | — |

**Finding:** the signature is never compared at run time against a graph that a topology event, a setting or a device edit may have changed. A change of the island's graph while it is certified ends the certificate where the change happens: topology (`TopologyChange` and `IslandCoordinator` line 583), an exchanger partner's commit (`ExchangerCoupling` 193 and 224 to 234), a property resume (583), and the horizon (`revalidate`). The digest's one live comparison (`restore`) hashes the graph that the same checkpoint holds. So the digest is a code revision written as a content hash, as R11 says. **Nothing had to be kept in another form.**

What the digest also did, and what covers it now:
- In `restore`, it found a codec that did not round-trip a field that the digest reads. `FluidCheckpointCodecTest`'s round trips (`assertSameImage`) and `FluidCheckpointStoreTest` cover the codec directly. RUNTIME_REVIEW R11 accepted this risk: "a decode drift would go unseen; the codec's round-trip validation covers it".
- In `Certified.equals`, it compared the certified graphs of two rigs in `PresentationBucketTest.scientificProgressIsIdenticalWithAndWithoutViewers`. The test still compares `state(a)` with `state(b)` (the graph each certificate materialises), the publication fingerprints and the replayed boundary bits. The certified interval's law fields are no longer compared there. This is a small loss of test strength, and no production path relies on it.

## 2. The change

- `IslandCertificate.REPLAY_REVISION = "replay-1"`. **When to bump it:** when a change alters what a replay holds fixed about an island (the laws, settings or node facts that a replay carries over from its interval's graph), or when the saved interval's decoding changes so that a restored certificate would replay another result. The javadoc says this.
- `Signature(propertyRevision, policy, replay)`. `replay` is checked by the token rule `[A-Za-z0-9_.-]{1,64}`. `Signature.of(model, policy)` takes no graph. `difference` names "replay revision".
- Deleted: `graphIdentity`, `Digest`, the `control` writer and four imports. That is 77 lines of digest code, 95 lines out of `IslandCertificate` in total, 26 added.
- `Saved.toString` names the replay revision. The `Certified.equals` comment no longer says that the signature digests the graph.
- Unchanged: issue, replay, the rest and steady rules, the policy values and `restore`'s horizon check.

### The checkpoint field (the brief's premise, corrected)

The brief takes the third component to be "still a string". It is not. The codec wrote the digest as **32 raw bytes** (`w.raw(HexFormat.of().parseHex(...))`, read as `r.raw(32)`), not as a string-table reference. The brief requires that the bytes and the version do not change, so I kept the field in place, kept its length (32) and kept the state format (50):
- `FluidCheckpointCodec.replayField(String)` writes the token's ASCII characters, zero-padded. It writes a 64-digit hex token as its own 32 bytes. It refuses a token longer than 32 characters, and a token that would read back as another one.
- `FluidCheckpointCodec.replayRevision(byte[])` reads a zero-padded token as that token. It reads anything else as the hex of the 32 bytes.
- For a current certificate, the bytes of the unit change only in this field: `replay-1` and 24 zeros, where the digest was before.

**What an old save does on load.** Its field holds a SHA-256 digest. A digest reads as a padded token only if all its non-zero bytes are token characters, followed only by zeros: a chance of about 1e-19. So it reads back as its 64 hex digits. That is a valid token, and it differs from `replay-1`. `restore` therefore reports "its replay revision changed": the island's status is `WAITING: saved certificate discarded: its replay revision changed`, the counter `certificatesDiscarded` counts it, and the island keeps its materialised inventory and solves again. No other state is dropped, and the load does not take the facts route. Even in the 1e-19 case, the token read back is never `replay-1`, so the certificate is still discarded.

**The alternative, for the main thread to choose instead:** write the revision as a third string-table reference and bump `FluidCheckpointCodec.VERSION` to 51 (the number must be reserved first, COLLABORATION.md section 3). This is cleaner bytes. But every old world's islands would then rebuild from their facts (route FACTS), not only drop their certificates, and it goes against the brief's "bytes and version do not change".

## 3. Tests

- **Removed:** the `"graph identity"` case of `FluidCheckpointFormatTest.aSignatureMismatchDiscardsTheCertificateAndKeepsTheInventory`, with its helper `withLongerSections`. It guarded that a saved certificate whose graph had every pipe run one metre longer was discarded on load (the digest's sensitivity to a section length). No other test pinned a law field in the digest.
- **Adapted:** the same test gets two `"replay revision"` cases: an older save's digest (`e3b0c442…b855`) and an older declared token (`replay-0`). Each is written through the codec (`CheckpointImages.withIsland` with the new helper `withReplayRevision`), read back and loaded. The test asserts that the certificate is discarded, the status names "its replay revision", the inventory is kept bit for bit, and the island solves its next interval.
- **Added:** `FluidCheckpointCodecTest.theReplayFieldHoldsTheDeclaredRevisionAndReadsAnOlderDigestAsItsHex`. `REPLAY_REVISION` round-trips in 32 bytes. An old digest's bytes read back as its hex digits and are written back as the same bytes. The hex token is a valid `Signature`. A token of 33 characters is refused by the field, and a token with a space is refused by the `Signature`.
- **Strengthened:** the `FluidCheckpointStoreTest` round trip (line 129) also asserts that a current certificate reads back `REPLAY_REVISION`.
- **Call sites only:** `CoolingTowerRuntimeTest` line 121, `FluidCheckpointStoreTest` line 328 and `tools/fluid-probes/f3/probes/F3Fixtures.java` line 170 now call `Signature.of(model, policy)`. F3Fixtures was already stale: it uses `saved.kind()` and `s.anchor()`, which no longer exist, and it is not compiled.
- **Not found:** `IslandCertificateRestoreTest`, which the brief names, does not exist. `IslandCertificateTest` does not refer to the signature.

## 4. Tests for the main thread (`test` group)

`./gradlew test --tests 'com.wormzjl.icheme.runtime.fluid.FluidCheckpointCodecTest' --tests 'com.wormzjl.icheme.runtime.fluid.FluidCheckpointFormatTest' --tests 'com.wormzjl.icheme.runtime.fluid.FluidCheckpointStoreTest' --tests 'com.wormzjl.icheme.runtime.fluid.CoolingTowerRuntimeTest' --tests 'com.wormzjl.icheme.runtime.fluid.IslandCertificateTest' --tests 'com.wormzjl.icheme.runtime.fluid.FluidFactsCompatTest' --tests 'com.wormzjl.icheme.runtime.fluid.PresentationBucketTest' --tests 'com.wormzjl.icheme.runtime.fluid.PhysicalRegistryTest' --tests 'com.wormzjl.icheme.runtime.fluid.MixedSetMergeTest' --tests 'com.wormzjl.icheme.science.fluid.network.VelocityClampTest' --tests 'com.wormzjl.icheme.FormatsTableTest'`

What the runs prove:
- The codec, format and store tests: the field, the discard of an old certificate on load, and the round trip of a current certificate.
- The rest tests (`IslandCertificateTest`, `PresentationBucketTest`, `PhysicalRegistryTest`, `MixedSetMergeTest`): certificates still issue and stay equal where nothing touched them.
- `FormatsTableTest`: the new row matches `IslandCertificate#REPLAY_REVISION`.
- The fluid GameTests (`FluidCheckpointGameTests`, certified reload) belong in the final lane.

## 5. Not verified without a compile

- `IslandCertificate.java`: the javadoc `{@link FluidCheckpointCodec#replayField}` points to a package-private method in the same package. This is a javadoc reference only.
- `FluidCheckpointCodec.java` lines 375 to 400: `Arrays`, `HexFormat` (`java.util.*`) and `StandardCharsets` are already imported. The local name `replay` at line 633 does not clash with other names in `island(...)`.
- `FluidCheckpointFormatTest` `withReplayRevision` uses the three-argument `IslandEntry` and the eight-argument `Snapshot` constructors. `withCertificateGraphs` in the same file already uses both. The `List.of` of cases now holds eight `Case` values.
- `IslandCertificate` still imports `SolidInventory` and `science.fluid.network.*`. Both are used by replay and the summary.

## 6. Left for others (not in this lane)

- `science/fluid/network/CoolingTower.java` line 77: `toString` is overridden "as before the fan limit … (an island's certificate signature reads it)". Nothing reads it for that reason now. The fluid-laws lane may delete the override and the comment.
- The history lines that name graph identities in the code guide (`fluid-runtime.md` batch list, `HISTORY.md`) and in older batch documents stay as history.
