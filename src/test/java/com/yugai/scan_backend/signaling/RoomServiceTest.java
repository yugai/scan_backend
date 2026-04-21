package com.yugai.scan_backend.signaling;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class RoomServiceTest {

    @Autowired
    private RoomService roomService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanUp() {
        var keys = redisTemplate.keys("room:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void createRoom_returnsRoomIdAndStoresCreator() {
        String roomId = roomService.createRoom("session-1");
        assertNotNull(roomId);
        assertEquals(36, roomId.length());

        var fields = redisTemplate.opsForHash().entries("room:" + roomId);
        assertEquals("session-1", fields.get("creator"));
        assertNull(fields.get("joiner"));
    }

    @Test
    void joinRoom_success() {
        String roomId = roomService.createRoom("session-1");
        boolean joined = roomService.joinRoom(roomId, "session-2");
        assertTrue(joined);

        var fields = redisTemplate.opsForHash().entries("room:" + roomId);
        assertEquals("session-2", fields.get("joiner"));
    }

    @Test
    void joinRoom_nonExistentRoom_returnsFalse() {
        boolean joined = roomService.joinRoom("non-existent", "session-2");
        assertFalse(joined);
    }

    @Test
    void joinRoom_fullRoom_returnsFalse() {
        String roomId = roomService.createRoom("session-1");
        roomService.joinRoom(roomId, "session-2");
        boolean joined = roomService.joinRoom(roomId, "session-3");
        assertFalse(joined);
    }

    @Test
    void getPeerSessionId_returnsOtherSession() {
        String roomId = roomService.createRoom("session-1");
        roomService.joinRoom(roomId, "session-2");

        assertEquals("session-2", roomService.getPeerSessionId(roomId, "session-1"));
        assertEquals("session-1", roomService.getPeerSessionId(roomId, "session-2"));
    }

    @Test
    void findRoomBySession_returnsRoomId() {
        String roomId = roomService.createRoom("session-1");
        assertEquals(roomId, roomService.findRoomBySession("session-1"));
    }

    @Test
    void removeSession_deletesRoomWhenEmpty() {
        String roomId = roomService.createRoom("session-1");
        roomService.removeSession("session-1");
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey("room:" + roomId)));
    }

    @Test
    void removeSession_keepsRoomWhenPeerRemains() {
        String roomId = roomService.createRoom("session-1");
        roomService.joinRoom(roomId, "session-2");
        roomService.removeSession("session-2");
        assertTrue(Boolean.TRUE.equals(redisTemplate.hasKey("room:" + roomId)));
        assertNull(redisTemplate.opsForHash().get("room:" + roomId, "joiner"));
    }
}
