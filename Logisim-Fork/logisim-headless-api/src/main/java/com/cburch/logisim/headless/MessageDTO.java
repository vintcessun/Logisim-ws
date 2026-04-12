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
