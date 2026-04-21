package com.yugai.scan_backend.signaling;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;

public record SignalingMessage(
    String action,
    String roomId,
    JsonNode payload
) {}
