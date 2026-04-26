package com.yugai.scan_backend.signaling;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SignalingHandlerTest {

    @LocalServerPort
    private int port;

    private final ObjectMapper mapper = new ObjectMapper();

    private TestClient connect() throws Exception {
        var client = new StandardWebSocketClient();
        var testClient = new TestClient();
        var session = client.execute(testClient, null, URI.create("ws://localhost:" + port + "/ws")).get(5, TimeUnit.SECONDS);
        testClient.session = session;
        return testClient;
    }

    @Test
    void createAndJoin_fullFlow() throws Exception {
        var web = connect();
        web.send("{\"action\":\"create\"}");
        JsonNode created = web.receive();
        assertEquals("room-created", created.get("event").asText());
        String roomId = created.get("roomId").asText();
        assertNotNull(roomId);

        var app = connect();
        app.send("{\"action\":\"join\",\"roomId\":\"" + roomId + "\"}");

        JsonNode peerJoined = web.receive();
        assertEquals("peer-joined", peerJoined.get("event").asText());

        app.send("{\"action\":\"signal\",\"payload\":{\"type\":\"offer\",\"sdp\":\"test\"}}");
        JsonNode signal = web.receive();
        assertEquals("signal", signal.get("event").asText());
        assertEquals("offer", signal.get("payload").get("type").asText());

        web.close();
        app.close();
    }

    @Test
    void joinNonExistentRoom_returnsError() throws Exception {
        var app = connect();
        app.send("{\"action\":\"join\",\"roomId\":\"non-existent\"}");
        JsonNode error = app.receive();
        assertEquals("error", error.get("event").asText());
        assertEquals("ROOM_NOT_FOUND", error.get("code").asText());
        app.close();
    }

    @Test
    void joinFullRoom_returnsError() throws Exception {
        var web = connect();
        web.send("{\"action\":\"create\"}");
        String roomId = web.receive().get("roomId").asText();

        var app1 = connect();
        app1.send("{\"action\":\"join\",\"roomId\":\"" + roomId + "\"}");
        web.receive(); // peer-joined

        var app2 = connect();
        app2.send("{\"action\":\"join\",\"roomId\":\"" + roomId + "\"}");
        JsonNode error = app2.receive();
        assertEquals("error", error.get("event").asText());
        assertEquals("ROOM_FULL", error.get("code").asText());

        web.close();
        app1.close();
        app2.close();
    }

    @Test
    void peerDisconnect_notifiesOther() throws Exception {
        var web = connect();
        web.send("{\"action\":\"create\"}");
        String roomId = web.receive().get("roomId").asText();

        var app = connect();
        app.send("{\"action\":\"join\",\"roomId\":\"" + roomId + "\"}");
        web.receive(); // peer-joined

        app.close();
        JsonNode peerLeft = web.receive();
        assertEquals("peer-left", peerLeft.get("event").asText());

        web.close();
    }

    static class TestClient extends TextWebSocketHandler {
        WebSocketSession session;
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final ObjectMapper mapper = new ObjectMapper();

        void send(String json) throws Exception {
            session.sendMessage(new TextMessage(json));
        }

        JsonNode receive() throws Exception {
            String msg = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(msg, "Timed out waiting for message");
            return mapper.readTree(msg);
        }

        void close() throws Exception {
            session.close();
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(message.getPayload());
        }
    }
}
