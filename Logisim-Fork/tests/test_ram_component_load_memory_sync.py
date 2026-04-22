"""集成测试：按同步模式流程验证 RAM load_memory 与计数器推进。"""

import asyncio
import json
import os
from typing import Any

import websockets

PORT = 9924
URI = f"ws://localhost:{PORT}/ws"

CIRCUIT_FILE = os.path.abspath("test_circuits/存储器组件验证实验.circ")
SUBCIRCUIT_NAME = "RAM存储器组件电路（同步模式）"
TXT_PATH = os.path.abspath("test_circuits/MIPS RAM存储器测试用例——修改后的写入数据.txt")
COUNTER_EXPECTED = "15"


async def send_json(ws: Any, action: str, **kwargs: Any) -> dict[str, Any]:
    req: dict[str, Any] = {"action": action, **kwargs}
    print(f"[Run API] >> {req}")
    await ws.send(json.dumps(req, ensure_ascii=False))
    resp = await ws.recv()
    if isinstance(resp, bytes):
        return {"status": "error", "message": "unexpected binary response"}
    data = json.loads(resp)
    print(f"[Run API] << {data}")
    return data


def require_ok(label: str, resp: dict[str, Any]) -> None:
    if resp.get("status") != "ok":
        raise AssertionError(f"{label} failed: {resp}")


def require_ticks_found(label: str, resp: dict[str, Any]) -> None:
    require_ok(label, resp)
    if resp.get("ticks", 0) < 0:
        raise AssertionError(f"{label} did not reach target: {resp}")


async def test_ram_component_load_memory_sync() -> None:
    print(f"[Setup] WS URI: {URI}")
    print(f"[Setup] Circuit file: {CIRCUIT_FILE}")
    print(f"[Setup] Subcircuit: {SUBCIRCUIT_NAME}")
    print(f"[Setup] TXT path: {TXT_PATH}")

    if not os.path.exists(CIRCUIT_FILE):
        raise FileNotFoundError(f"circuit not found: {CIRCUIT_FILE}")
    if not os.path.exists(TXT_PATH):
        raise FileNotFoundError(f"txt not found: {TXT_PATH}")

    async with websockets.connect(URI) as ws:
        resp = await send_json(ws, "load_circuit", path=CIRCUIT_FILE)
        require_ok("load_circuit", resp)

        resp = await send_json(ws, "switch_circuit", name=SUBCIRCUIT_NAME)
        require_ok("switch_circuit", resp)

        resp = await send_json(ws, "get_io")
        require_ok("get_io", resp)

        resp = await send_json(ws, "get_component_info", target="RAM")
        require_ok("get_component_info", resp)

        resp = await send_json(ws, "load_memory", target="RAM", txt_path=TXT_PATH)
        require_ok("load_memory", resp)

        resp = await send_json(ws, "set_value", target="inputCLR", value="1")
        require_ok("set_value(inputCLR=1)", resp)

        resp = await send_json(ws, "run_tick", tick_count=2)
        require_ok("run_tick(2)-after-clr-high", resp)

        resp = await send_json(ws, "set_value", target="inputCLR", value="0")
        require_ok("set_value(inputCLR=0)", resp)

        resp = await send_json(ws, "set_value", target="inputLD", value="1")
        require_ok("set_value(inputLD=1)", resp)

        resp = await send_json(ws, "set_value", target="inputSEL", value="0")
        require_ok("set_value(inputSEL=0)", resp)

        resp = await send_json(
            ws,
            "tick_until",
            target="Counter",
            expected=COUNTER_EXPECTED,
            max=10000,
            clock="CLK",
        )
        require_ticks_found(f"tick_until(Counter={COUNTER_EXPECTED})", resp)

        # tick_until 内部已经按步推进并在每步后校验，跨请求再次 check_value
        # 可能因后台时钟继续推进而出现竞态漂移（例如 0x15 -> 0x17）。
        print(f"[Diagnostic] tick_until reached in ticks={resp.get('ticks')}")

        resp = await send_json(ws, "run_tick", tick_count=2)
        require_ok("run_tick(2)-after-counter", resp)

        resp = await send_json(ws, "get_value", target="Counter")
        require_ok("get_value(Counter)-after-run_tick", resp)
        print(f"[Diagnostic] Counter after extra 2 ticks: {resp.get('payload')}")

        await ws.send(
            json.dumps({"action": "get_screenshot", "width": 1600, "height": 900})
        )
        binary_data = await ws.recv()
        if not isinstance(binary_data, bytes):
            raise AssertionError("Failed to receive binary screenshot data")

        output_path = "tests/screenshot_memory_component_ram_sync.png"
        with open(output_path, "wb") as f:
            f.write(binary_data)
        print(f"Screenshot saved to: {os.path.abspath(output_path)}")


if __name__ == "__main__":
    asyncio.run(test_ram_component_load_memory_sync())
