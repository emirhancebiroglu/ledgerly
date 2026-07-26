# Pinned versions

No `latest` tags anywhere in this repo (build/base images, lockfiles, CI). When a tool bumps a
version, update this file in the same commit.

| Tool | Version | Pinned at |
|---|---|---|
| Java (Temurin) | 21.0.11+10 | M1 T0 |
| Maven | 3.9.11 | M1 T1 |
| Spring Boot | 3.5.x | M1 T2 |
| Python | 3.12.0 | M1 T1 |
| Node.js | 24.14.0 | M1 T1 |
| Next.js | TBD — set at T4 scaffold | M1 T4 |
| shadcn/ui | TBD — set at T4 scaffold | M1 T4 |
| PostgreSQL (pgvector image) | `pgvector/pgvector:pg17` | M1 T2 |
| api base image | `eclipse-temurin:21.0.11_10-jdk-jammy` (build), `eclipse-temurin:21.0.11_10-jre-jammy` (runtime) | M1 T2 |
