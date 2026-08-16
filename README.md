# War of the Ring Mods (Fabric 1.21.8)

Two independent server-side Fabric mods.

- [`reinforcedclaims/`](reinforcedclaims/README.md) — block reinforcement, claims, snitches.
- [`waystones/`](waystones/README.md) — waystones and teleport networks.

## Build

```
./gradlew build
./gradlew :reinforcedclaims:build
./gradlew :waystones:build
./gradlew :reinforcedclaims:runServer
./gradlew :waystones:runServer
```

Jars land in `<subproject>/build/libs/`. Requires JDK 21.
