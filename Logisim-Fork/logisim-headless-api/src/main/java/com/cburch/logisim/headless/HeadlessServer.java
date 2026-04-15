package com.cburch.logisim.headless;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.websocket.WsConfig;
import java.time.Duration;
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

		Javalin app = Javalin
						  .create(config -> {
							  config.showJavalinBanner = false;
							  // Native support for Virtual Threads in Javalin 6 (if enabled in JVM)
							  // config.jetty.defaultThreadPool() is used by default.
						  })
						  .start(9924);

		app.ws("/ws", ws -> {
			ws.onConnect(ctx -> {
				ctx.session.setIdleTimeout(Duration.ZERO);
				logger.info("New WebSocket connection: {}", ctx.sessionId());
			});

			ws.onMessage(ctx -> {
				// Execute each message in a virtual thread to fulfill the "Session per Virtual
				// Thread"/Concurrency requirement
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
					if (session != null)
						session.close();
					try {
						session = new LogisimSessionContext(req.path);
						sessions.put(ctx.sessionId(), session);
						ctx.send(MessageDTO.ok(req.req_id));
					} catch (Exception e) {
						ctx.send(MessageDTO.error(req.req_id, e.getMessage()));
						break;
					}
					break;

				case "get_circuits":
					if (session == null) {
						ctx.send(MessageDTO.error(req.req_id, "No circuit loaded"));
						break;
					}
					MessageDTO resCircs = MessageDTO.ok(req.req_id);
					try {
						resCircs.payload = session.getCircuits();
						ctx.send(resCircs);
					} catch (Exception e) {
						ctx.send(MessageDTO.error(req.req_id, e.getMessage()));
					}
					break;

				case "switch_circuit":
					if (session == null) {
						ctx.send(MessageDTO.error(req.req_id, "No circuit loaded"));
						break;
					}
					try {
						session.switch_circuit(req.name);
						ctx.send(MessageDTO.ok(req.req_id));
					} catch (IllegalArgumentException e) {
						ctx.send(MessageDTO.error(req.req_id, e.getMessage()));
						break;
					}
					break;

				case "get_io":
					if (session == null) {
						ctx.send(MessageDTO.error(req.req_id, "No circuit loaded"));
						break;
					}
					MessageDTO resIo = MessageDTO.ok(req.req_id);
					try {
						resIo.payload = session.getIO();
						ctx.send(resIo);
					} catch (Exception e) {
						ctx.send(MessageDTO.error(req.req_id, e.getMessage()));
					}
					break;

				case "set_value":
					if (session == null) {
						ctx.send(MessageDTO.error(req.req_id, "No circuit loaded"));
						break;
					}
					try {
						session.setValue(req.target, req.value);
						ctx.send(MessageDTO.ok(req.req_id));
					} catch (IllegalArgumentException e) {
						ctx.send(MessageDTO.error(req.req_id, e.getMessage()));
					}
					break;

				case "get_value":
					if (session == null) {
						ctx.send(MessageDTO.error(req.req_id, "No circuit loaded"));
						break;
					}
					MessageDTO resVal = MessageDTO.ok(req.req_id);
					try {
						resVal.payload = session.getValue(req.target);
						ctx.send(resVal);
					} catch (Exception e) {
						ctx.send(MessageDTO.error(req.req_id, e.getMessage()));
					}
					break;

				case "tick_until":
					if (session == null) {
						ctx.send(MessageDTO.error(req.req_id, "No circuit loaded"));
						break;
					}
					try {
						int max = req.max != null ? req.max : 1000;
						int ticks = session.tickUntil(req.target, req.expected, req.clock, max);
						MessageDTO resTicks = MessageDTO.ok(req.req_id);
						resTicks.ticks = ticks;
						ctx.send(resTicks);
					} catch (IllegalArgumentException e) {
						ctx.send(MessageDTO.error(req.req_id, e.getMessage()));
					}
					break;

				case "check_value":
					if (session == null) {
						ctx.send(MessageDTO.error(req.req_id, "No circuit loaded"));
						break;
					}
					try {
						session.checkValue(req.target, req.expected);
						ctx.send(MessageDTO.ok(req.req_id));
					} catch (IllegalArgumentException e) {
						ctx.send(MessageDTO.error(req.req_id, e.getMessage()));
					}
					break;

				case "get_screenshot":
					if (session == null) {
						ctx.send(MessageDTO.error(req.req_id, "No circuit loaded"));
						break;
					}
					byte[] png =
						session.getScreenshot(0, 0); // Dimensions are now calculated automatically
					ctx.send(java.nio.ByteBuffer.wrap(png));
					break;

				case "get_component_info":
					if (session == null) {
						ctx.send(MessageDTO.error(req.req_id, "No circuit loaded"));
						break;
					}
					try {
						MessageDTO resInfo = MessageDTO.ok(req.req_id);
						resInfo.payload = session.getComponentInfo(req.target);
						ctx.send(resInfo);
					} catch (IllegalArgumentException e) {
						ctx.send(MessageDTO.error(req.req_id, e.getMessage()));
					}
					break;

				case "load_memory":
					if (session == null) {
						ctx.send(MessageDTO.error(req.req_id, "No circuit loaded"));
						break;
					}
					if (req.target == null || req.target.trim().isEmpty()) {
						ctx.send(
							MessageDTO.error(req.req_id, "'target' is required for load_memory."));
						break;
					}
					try {
						if (req.txt_path != null && !req.txt_path.trim().isEmpty()) {
							session.loadMemoryFromTxt(req.target, req.txt_path);
						} else if (req.contents != null && !req.contents.isEmpty()) {
							// Backward compatibility with old payload mode.
							session.loadMemory(req.target, req.contents);
						} else {
							ctx.send(MessageDTO.error(req.req_id,
								"load_memory requires 'txt_path' (preferred) or non-empty "
									+ "'contents'."));
							break;
						}
						ctx.send(MessageDTO.ok(req.req_id));
					} catch (IllegalArgumentException e) {
						ctx.send(MessageDTO.error(req.req_id, e.getMessage()));
					}
					break;

				case "run_until_stable_then_tick":
					if (session == null) {
						ctx.send(MessageDTO.error(req.req_id, "No circuit loaded"));
						break;
					}
					if (req.target == null || req.target.trim().isEmpty()) {
						ctx.send(MessageDTO.error(
							req.req_id, "'target' is required for run_until_stable_then_tick."));
						break;
					}
					if (req.timeout_second == null || req.timeout_second <= 0) {
						ctx.send(MessageDTO.error(req.req_id,
							"'timeout_second' is required and must be > 0 for "
								+ "run_until_stable_then_tick."));
						break;
					}
					try {
						int k = req.k != null ? req.k : 0;
						int stableSamples = req.stable_samples != null ? req.stable_samples : 5;
						int pollMs = req.poll_ms != null ? req.poll_ms : 20;

						MessageDTO res = MessageDTO.ok(req.req_id);
						res.payload = session.runUntilStableThenTick(
							req.target, req.expected, k, req.timeout_second, stableSamples, pollMs);
						ctx.send(res);
					} catch (IllegalArgumentException e) {
						ctx.send(MessageDTO.error(req.req_id, e.getMessage()));
					}
					break;

				case "run_tick":
					if (session == null) {
						ctx.send(MessageDTO.error(req.req_id, "No circuit loaded"));
						break;
					}
					if (req.tick_count == null || req.tick_count <= 0) {
						ctx.send(MessageDTO.error(
							req.req_id, "'tick_count' is required and must be > 0 for run_tick."));
						break;
					}
					try {
						int ticks = session.runTick(req.tick_count);
						MessageDTO resTick = MessageDTO.ok(req.req_id);
						resTick.ticks = ticks;
						ctx.send(resTick);
					} catch (IllegalArgumentException e) {
						ctx.send(MessageDTO.error(req.req_id, e.getMessage()));
					}
					break;

				default:
					ctx.send(MessageDTO.error(req.req_id, "Unknown action: " + req.action));
			}

		} catch (Exception e) {
			logger.error("Error handling message", e);
			try {
				ctx.send(MessageDTO.error(null, "Internal error: " + e.getMessage()));
			} catch (Exception ignored) {
			}
		}
	}
}
