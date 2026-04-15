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
| `check_value` | `target` (String), `expected` (String) | Assert that a component's value matches the expected value. |
| `get_component_info` | `target` (String) | Get component type and memory metadata (if available). |
| `load_memory` | `target` (String), `txt_path` (String) | Load ROM contents from a txt file in Logisim hex format (`v2.0 raw`). |
| `run_until_stable_then_tick` | `target` (String), `timeout_second` (Number), `k`? (Int), `stable_samples`? (Int), `poll_ms`? (Int) | Reset + run continuous clock at 4.1kHz until target is stable, then execute extra `k` ticks. |

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

## 🧠 Memory APIs

### `get_component_info`

Request:
```json
{
  "action": "get_component_info",
  "target": "主存内容",
  "req_id": "info-1"
}
```

Response payload example:
```json
{
  "type": "ROM",
  "label": "主存内容",
  "isMemory": true,
  "addrBits": 16,
  "dataBits": 8,
  "capacity": 65536
}
```

### `load_memory`

Request (recommended):
```json
{
  "action": "load_memory",
  "target": "主存地址",
  "txt_path": "D:/.../cacahe 测试用例——主存地址.txt",
  "req_id": "load-1"
}
```

`target` 必须是当前电路中已标注的 ROM 组件标签。建议先调用 `get_component_info`，确认 `type` 为 `ROM` 后再加载。

### `load_memory` txt file format

目前 `load_memory` 的 `txt_path` 采用 Logisim Hex 文件格式：**`v2.0 raw`**。

示例：
```text
v2.0 raw
0 1 2 3 8 9 a b
10 11 12 13 20 21 22 23
8 9 a b 4 5 6 7
```

说明：
- 第一行必须是 `v2.0 raw`
- 后续为十六进制数据流，按地址从 0 开始顺序写入
- 数据超过目标 ROM 容量时会返回错误

> Backward compatibility: `load_memory` 仍兼容旧模式 `contents`（地址到值的 map），但推荐统一使用 `txt_path`。

## ⌨️ Shortcut Mapping (Simulation)

- `Ctrl+R`: Reset simulation state（对应菜单中的复位）
- `Ctrl+K`: Continuous ticking clock（对应菜单中的连续时钟）

在本 API 中，`run_until_stable_then_tick` 会按这个顺序执行：
1. Reset simulation（等价 `Ctrl+R`）
2. Start continuous clock at max frequency `4.1kHz`（等价 `Ctrl+K`）
3. 轮询目标值直到稳定
4. 再额外执行 `k` 次 tick

## ▶️ Macro Command: `run_until_stable_then_tick`

### Request

```json
{
  "action": "run_until_stable_then_tick",
  "target": "访问次数",
  "timeout_second": 10,
  "k": 100,
  "stable_samples": 5,
  "poll_ms": 20,
  "req_id": "macro-1"
}
```

### Parameters

- `target` (required): 需要观察是否稳定的标签。
- `timeout_second` (required): 超时时间（秒）。例如 `10` 表示最多等待 10 秒。
- `k` (optional, default `0`): 稳定后额外执行的 tick 次数。
- `stable_samples` (optional, default `5`): 连续多少次读值相同视为稳定。
- `poll_ms` (optional, default `20`): 轮询间隔毫秒。

### Timeout behavior

如果在 `timeout_second` 内没有达到稳定状态，或稳定后执行 `k` 次 tick 期间超时，返回：

```json
{
  "status": "error",
  "message": "Timeout after 10.0s while waiting for stable target '访问次数'."
}
```

### Success payload example

```json
{
  "target": "访问次数",
  "stable_value": "0x0064",
  "final_value": "0x00c8",
  "stable_samples": 5,
  "poll_count": 34,
  "k_executed": 100,
  "tick_frequency_hz": 4100.0,
  "elapsed_ms": 2874
}
```

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
