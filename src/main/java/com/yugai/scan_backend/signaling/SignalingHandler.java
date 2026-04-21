package com.yugai.scan_backend.signaling;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SignalingHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SignalingHandler.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final RoomService roomService;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public SignalingHandler(RoomService roomService) {
        this.roomService = roomService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("[ws] connected: sessionId={}", session.getId());
        sessions.put(session.getId(), session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        log.info("[ws] received from {}: {}", session.getId(), message.getPayload());
        SignalingMessage msg = mapper.readValue(message.getPayload(), SignalingMessage.class);
        switch (msg.action()) {
            case "create" -> handleCreate(session);
            case "join" -> handleJoin(session, msg.roomId());
            case "signal" -> handleSignal(session, msg.payload());
            default -> sendEvent(session, SignalingEvent.error("UNKNOWN_ACTION", "Unknown action: " + msg.action()));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = session.getId();
        log.info("[ws] disconnected: sessionId={}, status={}", sessionId, status);
        sessions.remove(sessionId);
        String roomId = roomService.findRoomBySession(sessionId);
        if (roomId != null) {
            log.info("[ws] session {} was in room {}", sessionId, roomId);
            String peerId = roomService.getPeerSessionId(roomId, sessionId);
            roomService.removeSession(sessionId);
            if (peerId != null) {
                WebSocketSession peerSession = sessions.get(peerId);
                if (peerSession != null && peerSession.isOpen()) {
                    log.info("[ws] notifying peer {} of peer-left", peerId);
                    sendEvent(peerSession, SignalingEvent.peerLeft());
                }
            }
        }
    }

    private void handleCreate(WebSocketSession session) {
        String roomId = roomService.createRoom(session.getId());
        log.info("[room] created: roomId={}, creator={}", roomId, session.getId());
        sendEvent(session, SignalingEvent.roomCreated(roomId));
    }

    private void handleJoin(WebSocketSession session, String roomId) {
        if (roomId == null || roomId.isBlank()) {
            log.warn("[room] join failed: empty roomId from {}", session.getId());
            sendEvent(session, SignalingEvent.error("ROOM_NOT_FOUND", "Room ID is required"));
            return;
        }
        log.info("[room] join attempt: roomId={}, joiner={}", roomId, session.getId());
        boolean joined = roomService.joinRoom(roomId, session.getId());
        if (!joined) {
            String existingPeer = roomService.getPeerSessionId(roomId, session.getId());
            if (existingPeer == null) {
                log.warn("[room] join failed: room {} not found or expired", roomId);
                sendEvent(session, SignalingEvent.error("ROOM_NOT_FOUND", "Room not found or expired"));
            } else {
                log.warn("[room] join failed: room {} is full", roomId);
                sendEvent(session, SignalingEvent.error("ROOM_FULL", "Room is full"));
            }
            return;
        }
        log.info("[room] joined: roomId={}, joiner={}", roomId, session.getId());
        String peerId = roomService.getPeerSessionId(roomId, session.getId());
        if (peerId != null) {
            WebSocketSession peerSession = sessions.get(peerId);
            if (peerSession != null && peerSession.isOpen()) {
                log.info("[room] notifying creator {} of peer-joined", peerId);
                sendEvent(peerSession, SignalingEvent.peerJoined());
            } else {
                log.warn("[room] creator peer {} not found or closed", peerId);
            }
        } else {
            log.warn("[room] no peer found for joiner {} in room {}", session.getId(), roomId);
        }
    }

    private void handleSignal(WebSocketSession session, Object payload) {
        String roomId = roomService.findRoomBySession(session.getId());
        if (roomId == null) {
            log.warn("[signal] no room found for session {}", session.getId());
            return;
        }
        String peerId = roomService.getPeerSessionId(roomId, session.getId());
        if (peerId == null) {
            log.warn("[signal] no peer found for session {} in room {}", session.getId(), roomId);
            return;
        }
        WebSocketSession peerSession = sessions.get(peerId);
        if (peerSession != null && peerSession.isOpen()) {
            log.info("[signal] relaying from {} to {} in room {}", session.getId(), peerId, roomId);
            sendEvent(peerSession, SignalingEvent.signal(payload));
        } else {
            log.warn("[signal] peer session {} not open", peerId);
        }
    }

    private void sendEvent(WebSocketSession session, SignalingEvent event) {
        try {
            String json = mapper.writeValueAsString(event);
            log.info("[ws] sending to {}: {}", session.getId(), json);
            session.sendMessage(new TextMessage(json));
        } catch (IOException e) {
            log.error("[ws] failed to send to {}: {}", session.getId(), e.getMessage());
        }
    }
}
