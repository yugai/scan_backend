# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build
./gradlew build

# Run (requires Redis on localhost:6379)
./gradlew bootRun

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.yugai.scan_backend.signaling.SignalingHandlerTest"

# Run a single test method
./gradlew test --tests "com.yugai.scan_backend.signaling.SignalingHandlerTest.createAndJoin_fullFlow"
```

Integration tests (`SignalingHandlerTest`) spin up a real Spring Boot server on a random port and require Redis to be running locally.

## Architecture

This is a **WebRTC signaling server** — it coordinates peer discovery and SDP/ICE exchange between two devices, but does not relay media.

**Tech stack:** Spring Boot 4.0.5, Java 17, Spring WebSocket (raw WebSocket, not STOMP), Spring Data Redis, Lombok.

### Request/Response Flow

```
Client → WS /ws → SignalingHandler → RoomService (Redis + in-memory map)
                         ↓
                  Peer WebSocketSession (push events)
```

### Key Design Decisions

**Room state is split across two stores:**
- **Redis** (`room:{uuid}` hash with `creator` and `joiner` fields, 5-minute TTL): authoritative room membership, survives restarts.
- **`sessionToRoom` ConcurrentHashMap** in `RoomService`: in-memory reverse index (sessionId → roomId) for O(1) lookup. This map is *not* persisted — sessions are transient anyway.
- **`sessions` ConcurrentHashMap** in `SignalingHandler`: maps sessionId → live `WebSocketSession` object (cannot be stored in Redis).

**Rooms are exactly 2-party** (creator + joiner). A third join attempt returns `ROOM_FULL`.

**Ownership transfer on disconnect:** If the creator disconnects while a joiner is present, the joiner becomes the new creator in Redis (so they can still be found by `getPeerSessionId`).

### Message Protocol

**Inbound** (`SignalingMessage` record):
| `action` | Required fields | Effect |
|----------|----------------|--------|
| `create` | — | Creates room, responds `room-created` with roomId |
| `join` | `roomId` | Joins room, notifies creator with `peer-joined` |
| `signal` | `payload` | Relays payload to peer as `signal` event |

**Outbound** (`SignalingEvent` record, `@JsonInclude(NON_NULL)`):
`room-created`, `peer-joined`, `signal`, `peer-left`, `error` (with `code` + `message`)

### Package Structure

```
com.yugai.scan_backend
├── ScanBackendApplication.java
├── config/
│   └── WebSocketConfig.java        # registers /ws endpoint, allows all origins
└── signaling/
    ├── SignalingHandler.java        # WebSocket lifecycle + message dispatch
    ├── RoomService.java             # room CRUD, Redis + in-memory state
    ├── SignalingMessage.java        # inbound record (action, roomId, payload)
    └── SignalingEvent.java          # outbound record with static factories
```

### Jackson Note

`SignalingMessage` uses `tools.jackson.databind` (Jackson 3.x bundled with Spring Boot 4), while `SignalingEvent` uses `com.fasterxml.jackson.annotation` (Jackson 2.x annotation still present). Keep deserialization imports from `tools.jackson` and serialization annotations from `com.fasterxml.jackson` until the project fully migrates.
