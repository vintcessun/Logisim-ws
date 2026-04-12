# Logisim Headless WebSocket API

This subproject provides a high-performance headless mode for Logisim, enabling remote control, automated simulation, and visual verification via a unified WebSocket interface.

## 🚀 Connection
- **URL**: `ws://localhost:9924/ws`
- **Port**: `9924` (Default)
- **Protocol**: Single WebSocket channel supporting JSON control frames and Binary response frames.

## 🛠 Protocol Specification

### Control Request (Text Frame - JSON)
All requests must include a `req_id` (String) to match responses asynchronously.

| Action | Parameters | Description |
| :--- | :--- | :--- |
| `load_circuit` | `path` (String) | Load or reload a `.circ` file path. |
| `get_circuits` | - | Return a list of all subcircuit names in the project. |
| `switch_circuit` | `name` (String) | Switch the simulation focus to the specified subcircuit. |
| `get_io` | - | Discover all labeled Pins and Tunnels in the current circuit. |
| `set_value` | `target` (String), `value` (String) | Drive a component (Pin/Register) with a value (Hex `0xAA` or Dec `10`). |
| `get_value` | `target` (String) | Read the current value of a component. |
| `tick_until` | `target` (String), `expected` (String), `max` (Int), `clock`? (String) | Execute simulation loops until target matches expectation. |
| `get_screenshot` | `width`? (Int), `height`? (Int) | Render the circuit. Response is a **Binary Frame**. |

> [!IMPORTANT]
> **Advanced Simulation (tick_until)**:
> - **Internal Tick**: If `clock` is omitted, the simulator advances internal `Clock` components by one tick per cycle.
> - **Pin Toggling**: If `clock` is provided (e.g., `"CLK"`), the API will pulse that pin (set to `1`, then `0`) in each cycle. This is perfect for driving state machines like multipliers.

### Response Format
```json
{
  "status": "ok | error",
  "req_id": "your_request_id",
  "payload": { ... },
  "ticks": 5,        // Only for tick_until
  "message": "Error details if applicable"
}
```

## 📸 Binary Feedback
When `get_screenshot` is called, the server will immediately transmit a **Binary Frame** containing raw PNG image bytes.

## 💡 Example: Running a Counter
1. **Load**: `{"action": "load_circuit", "path": "test.circ", "req_id": "id1"}`
2. **Setup**: `{"action": "set_value", "target": "RST", "value": "1", "req_id": "id2"}`
3. **Reset**: `{"action": "set_value", "target": "RST", "value": "0", "req_id": "id3"}`
4. **Run**: `{"action": "tick_until", "target": "OUT", "expected": "0x0F", "max": 16, "clock": "CLK", "req_id": "id4"}`
5. **Snap**: `{"action": "get_screenshot", "req_id": "id5"}`

## 🔧 Technical Stack
- **Engine**: Java 21 + Virtual Threads for massive concurrency.
- **Server**: Javalin 6 + Jetty 11.
- **Rendering**: Off-screen `Graphics2D` rendering with High-Quality Antialiasing.
