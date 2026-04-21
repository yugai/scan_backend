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
        sessions.put(session.getId(), session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
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
        sessions.remove(sessionId);
        String roomId = roomService.findRoomBySession(sessionId);
        if (roomId != null) {
            String peerId = roomService.getPeerSessionId(roomId, sessionId);
            roomService.removeSession(sessionId);
            if (peerId != null) {
                WebSocketSession peerSession = sessions.get(peerId);
                if (peerSession != null && peerSession.isOpen()) {
                    sendEvent(peerSession, SignalingEvent.peerLeft());
                }
            }
        }
    }

    private void handleCreate(WebSocketSession session) {
        String roomId = roomService.createRoom(session.getId());
        sendEvent(session, SignalingEvent.roomCreated(roomId));
    }

    private void handleJoin(WebSocketSession session, String roomId) {
        if (roomId == null || roomId.isBlank()) {
            sendEvent(session, SignalingEvent.error("ROOM_NOT_FOUND", "Room ID is required"));
            return;
        }
        boolean joined = roomService.joinRoom(roomId, session.getId());
        if (!joined) {
            // Check if room exists to distinguish not-found vs full
            String existingPeer = roomService.getPeerSessionId(roomId, session.getId());
            if (existingPeer == null) {
                sendEvent(session, SignalingEvent.error("ROOM_NOT_FOUND", "Room not found or expired"));
            } else {
                sendEvent(session, SignalingEvent.error("ROOM_FULL", "Room is full"));
            }
            return;
        }
        String peerId = roomService.getPeerSessionId(roomId, session.getId());
        if (peerId != null) {
            WebSocketSession peerSession = sessions.get(peerId);
            if (peerSession != null && peerSession.isOpen()) {
                sendEvent(peerSession, SignalingEvent.peerJoined());
            }
        }
    }

    private void handleSignal(WebSocketSession session, Object payload) {
        String roomId = roomService.findRoomBySession(session.getId());
        if (roomId == null) return;
        String peerId = roomService.getPeerSessionId(roomId, session.getId());
        if (peerId == null) return;
        WebSocketSession peerSession = sessions.get(peerId);
        if (peerSession != null && peerSession.isOpen()) {
            sendEvent(peerSession, SignalingEvent.signal(payload));
        }
    }

    private void sendEvent(WebSocketSession session, SignalingEvent event) {
        try {
            String json = mapper.writeValueAsString(event);
            session.sendMessage(new TextMessage(json));
        } catch (IOException e) {
            log.error("Failed to send event to session {}", session.getId(), e);
        }
    }
}
