# Native Quarkus Compatibility Tracker

Status: JVM mode only (Phase 0)

## Dependencies

| Dependency | Version | Native Status | Notes |
|---|---|---|---|
| quarkus-rest | 3.34.2 | ✅ Supported | |
| quarkus-rest-jackson | 3.34.2 | ✅ Supported | |
| quarkus-scheduler | 3.34.2 | ✅ Supported | |
| casehub-core | 1.0.0-SNAPSHOT | 🔲 Not verified | Verify before native build |
| ocraft-s2client-bot | 0.4.21 | 🔲 Not added yet | Added in Phase 1; uses RxJava + Protobuf |

## Rules (enforce these always)
- No dynamic class loading or runtime code generation
- All CDI injection via constructor or field — no programmatic Arc.container() lookups
- Reflection usages → register in src/main/resources/reflection-config.json
- No raw use of Class.forName()

## Known Issues
(none yet — updated as issues are discovered)
