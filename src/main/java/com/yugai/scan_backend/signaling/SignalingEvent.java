package com.yugai.scan_backend.signaling;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SignalingEvent(
    String event,
    String roomId,
    Object payload,
    String code,
    String message
) {
    public static SignalingEvent roomCreated(String roomId) {
        return new SignalingEvent("room-created", roomId, null, null, null);
    }

    public static SignalingEvent peerJoined() {
        return new SignalingEvent("peer-joined", null, null, null, null);
    }

    public static SignalingEvent signal(Object payload) {
        return new SignalingEvent("signal", null, payload, null, null);
    }

    public static SignalingEvent peerLeft() {
        return new SignalingEvent("peer-left", null, null, null, null);
    }

    public static SignalingEvent error(String code, String message) {
        return new SignalingEvent("error", null, null, code, message);
    }
}
