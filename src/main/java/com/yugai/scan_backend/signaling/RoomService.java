package com.yugai.scan_backend.signaling;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RoomService {

    private static final Duration ROOM_TTL = Duration.ofSeconds(300);
    private static final String KEY_PREFIX = "room:";

    private final StringRedisTemplate redis;
    private final Map<String, String> sessionToRoom = new ConcurrentHashMap<>();

    public RoomService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String createRoom(String sessionId) {
        String roomId = UUID.randomUUID().toString();
        String key = KEY_PREFIX + roomId;
        redis.opsForHash().put(key, "creator", sessionId);
        redis.expire(key, ROOM_TTL);
        sessionToRoom.put(sessionId, roomId);
        return roomId;
    }

    public boolean joinRoom(String roomId, String sessionId) {
        String key = KEY_PREFIX + roomId;
        if (!Boolean.TRUE.equals(redis.hasKey(key))) {
            return false;
        }
        Object existing = redis.opsForHash().get(key, "joiner");
        if (existing != null) {
            return false;
        }
        redis.opsForHash().put(key, "joiner", sessionId);
        sessionToRoom.put(sessionId, roomId);
        return true;
    }

    public String getPeerSessionId(String roomId, String mySessionId) {
        String key = KEY_PREFIX + roomId;
        Map<Object, Object> fields = redis.opsForHash().entries(key);
        String creator = (String) fields.get("creator");
        String joiner = (String) fields.get("joiner");
        if (mySessionId.equals(creator)) return joiner;
        if (mySessionId.equals(joiner)) return creator;
        return null;
    }

    public String findRoomBySession(String sessionId) {
        return sessionToRoom.get(sessionId);
    }

    public void removeSession(String sessionId) {
        String roomId = sessionToRoom.remove(sessionId);
        if (roomId == null) return;
        String key = KEY_PREFIX + roomId;
        Map<Object, Object> fields = redis.opsForHash().entries(key);
        if (fields.isEmpty()) return;

        String creator = (String) fields.get("creator");
        String joiner = (String) fields.get("joiner");

        if (sessionId.equals(creator) && joiner == null) {
            redis.delete(key);
        } else if (sessionId.equals(joiner)) {
            redis.opsForHash().delete(key, "joiner");
        } else if (sessionId.equals(creator) && joiner != null) {
            redis.opsForHash().put(key, "creator", joiner);
            redis.opsForHash().delete(key, "joiner");
            sessionToRoom.put(joiner, roomId);
        }
    }
}
