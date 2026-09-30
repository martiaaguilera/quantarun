# ADR-0006: Development environment and toolchain pins

- Status: accepted (2026-09-30)

## Context
The brief prefers development inside WSL2. On this machine, Claude Code, the JDKs, Node and the language
servers run on the Windows host. The only WSL distribution is Docker Desktop's internal one.

## Decision
- Build on the Windows host with the Maven wrapper and npm. Docker Desktop (WSL2 backend) runs PostgreSQL,
  Testcontainers and the compose stack. GitHub Actions on Ubuntu is the Linux verification gate. Moving the
  checkout into a WSL distro is a documented option, not a requirement.
- `.gitattributes` forces LF so scripts and SQL behave the same on both systems.
- Versions were verified on 2026-09-30 against start.spring.io, nodejs.org, npm and Docker Hub:

| Component | Version | Note |
|---|---|---|
| Java | 25 LTS (Temurin 25.0.4) | 27 exists but is not LTS |
| Spring Boot | 4.1.1 | 4.2 is milestone-only |
| PostgreSQL | 18.6 | `postgres:18.6-alpine` |
| Node | 24.21 LTS "Krypton" | 26 is current, not LTS |
| React | 19.3 | |
| Vite | 8.3 | |
| TypeScript | **6.0.3, not 7.0** | typescript-eslint 8.71 supports `<6.1`. Pinning keeps linting working; revisit when typescript-eslint supports 7 |

## Consequences
- Line-ending and path issues are caught by CI on Linux.
- A contributor on macOS or Linux follows the same commands; only Docker is required.
