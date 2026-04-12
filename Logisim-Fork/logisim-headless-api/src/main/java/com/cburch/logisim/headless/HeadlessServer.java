package com.cburch.logisim.headless;

import io.javalin.Javalin;
import io.javalin.websocket.WsConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HeadlessServer {
    private static final Logger logger = LoggerFactory.getLogger(HeadlessServer.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Map<String, LogisimSessionContext> sessions = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");

        Javalin app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            // Native support for Virtual Threads in Javalin 6 (if enabled in JVM)
            // config.jetty.defaultThreadPool() is used by default.
        }).start(9924);

        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                logger.info("New WebSocket connection: {}", ctx.sessionId());
            });

            ws.onMessage(ctx -> {
                // Execute each message in a virtual thread to fulfill the "Session per Virtual Thread"/Concurrency requirement
                Thread.ofVirtual().start(() -> handleMessage(ctx));
            });

            ws.onClose(ctx -> {
                logger.info("WebSocket connection closed: {}", ctx.sessionId());
                LogisimSessionContext session = sessions.remove(ctx.sessionId());
                if (session != null) {
                    session.close();
                }
            });

            ws.onError(ctx -> {
                logger.error("WebSocket error in session {}: {}", ctx.sessionId(), ctx.error());
            });
        });

        logger.info("Logisim Headless Server started on port 9924");
        logger.info("WebSocket endpoint: ws://localhost:9924/ws");
    }

    private static void handleMessage(io.javalin.websocket.WsMessageContext ctx) {
        String msgStr = ctx.message();
        try {
            MessageDTO req = mapper.readValue(msgStr, MessageDTO.class);
            LogisimSessionContext session = sessions.get(ctx.sessionId());

            if (req.action == null) {
                ctx.send(MessageDTO.error(req.req_id, "Missing action"));
                return;
            }

            switch (req.action) {
                case "load_circuit":
                    if (session != null) session.close();
                    session = new LogisimSessionContext(req.path);
                    sessions.put(ctx.sessionId(), session);
                    ctx.send(MessageDTO.ok(req.req_id));
                    break;

                case "get_circuits":
                    if (session == null) { ctx.send(MessageDTO.error(req.req_id, "No circuit loaded")); break; }
                    MessageDTO resCircs = MessageDTO.ok(req.req_id);
                    resCircs.payload = session.getCircuits();
                    ctx.send(resCircs);
                    break;

                case "switch_circuit":
                    if (session == null) { ctx.send(MessageDTO.error(req.req_id, "No circuit loaded")); break; }
                    session.switch_circuit(req.name);
                    ctx.send(MessageDTO.ok(req.req_id));
                    break;

                case "get_io":
                    if (session == null) { ctx.send(MessageDTO.error(req.req_id, "No circuit loaded")); break; }
                    MessageDTO resIo = MessageDTO.ok(req.req_id);
                    resIo.payload = session.getIO();
                    ctx.send(resIo);
                    break;

                case "set_value":
                    if (session == null) { ctx.send(MessageDTO.error(req.req_id, "No circuit loaded")); break; }
                    session.setValue(req.target, req.value);
                    ctx.send(MessageDTO.ok(req.req_id));
                    break;

                case "get_value":
                    if (session == null) { ctx.send(MessageDTO.error(req.req_id, "No circuit loaded")); break; }
                    MessageDTO resVal = MessageDTO.ok(req.req_id);
                    resVal.payload = session.getValue(req.target);
                    ctx.send(resVal);
                    break;

                case "tick_until":
                    if (session == null) { ctx.send(MessageDTO.error(req.req_id, "No circuit loaded")); break; }
                    int max = req.max != null ? req.max : 1000;
                    int ticks = session.tickUntil(req.target, req.expected, req.clock, max);
                    MessageDTO resTicks = MessageDTO.ok(req.req_id);
                    resTicks.ticks = ticks;
                    ctx.send(resTicks);
                    break;

                case "get_screenshot":
                    if (session == null) { ctx.send(MessageDTO.error(req.req_id, "No circuit loaded")); break; }
                    int w = req.width != null ? req.width : 1920;
                    int h = req.height != null ? req.height : 1080;
                    byte[] png = session.getScreenshot(w, h);
                    ctx.send(java.nio.ByteBuffer.wrap(png));
                    break;

                default:
                    ctx.send(MessageDTO.error(req.req_id, "Unknown action: " + req.action));
            }

        } catch (Exception e) {
            logger.error("Error handling message", e);
            try {
                ctx.send(MessageDTO.error(null, "Internal error: " + e.getMessage()));
            } catch (Exception ignored) {}
        }
    }
}
