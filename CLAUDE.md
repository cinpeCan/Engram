# CLAUDE.md

Read and follow [AGENTS.md](AGENTS.md) as the primary architectural and engineering specification for this repository.

## Quick Verification Commands

- **Android full debug build + JVM tests**: `./gradlew testDebugUnitTest assembleDebug`
- **Edge MNN runtime build + JVM tests**: `./gradlew :packages:edge-mnn:testDebugUnitTest :packages:edge-mnn:assembleDebug`
- **FastAPI gateway contract tests**: `server/.venv/bin/pytest server/tests`
- **Launch local gateway server**: `./scripts/dev_server.sh`
