# Intended Module Split

Currently a single Maven module. Extract when a plugin implementation matures.

## Planned Future Modules

| Module | Extract when | Contains |
|---|---|---|
| starcraft-sc2 | Phase 1 completes | sc2/, sc2/mock/, sc2/real/ |
| starcraft-domain | Phase 1 completes | domain/ |
| starcraft-agent | Phase 3 — first real plugin | agent/ + plugin interfaces |
| starcraft-agent-drools | Drools plugin matures | Drools TaskDefinition implementations |
| starcraft-agent-flow | Flow plugin matures | Quarkus Flow Worker implementations |
