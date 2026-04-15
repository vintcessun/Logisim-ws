package com.cburch.logisim.headless;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Common message format for WebSocket communication.
 */
public class MessageDTO {
	public String action;
	public String req_id;

	// Payload fields
	public String path;
	public String name;
	public String target;
	public String value;
	public String expected;
	public Integer max;
	public Integer width;
	public Integer height;
	public String clock;
	/** For run_until_stable_then_tick: timeout in seconds (required). */
	public Double timeout_second;
	/** For run_until_stable_then_tick: extra simulation ticks after stable. */
	public Integer k;
	/** For run_until_stable_then_tick: stable sample count. */
	public Integer stable_samples;
	/** For run_until_stable_then_tick: polling interval in milliseconds. */
	public Integer poll_ms;
	/** For load_memory: path to a v2.0 raw txt file. */
	public String txt_path;
	/** For load_memory: map from hex/decimal address string to integer value. */
	public Map<String, Integer> contents;

	// Response fields
	public String status;
	public Object payload;
	public Integer ticks;
	public String message;

	public MessageDTO() {}

	public static MessageDTO ok(String req_id) {
		MessageDTO m = new MessageDTO();
		m.status = "ok";
		m.req_id = req_id;
		return m;
	}

	public static MessageDTO error(String req_id, String msg) {
		MessageDTO m = new MessageDTO();
		m.status = "error";
		m.req_id = req_id;
		m.message = msg;
		return m;
	}
}
