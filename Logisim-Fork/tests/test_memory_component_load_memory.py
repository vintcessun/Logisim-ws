"""集成测试：复现并诊断 load_memory(ROM, txt_path) 在存储器组件验证实验中的行为。"""

import asyncio
import json
import os
from typing import Any

import websockets

PORT = 9924
URI = f"ws://localhost:{PORT}/ws"

CIRCUIT_FILE = os.path.abspath("test_circuits/存储器组件验证实验.circ")
TXT_PATH = os.path.abspath("test_circuits/ROM 内容.txt")
TARGET = "ROM"


async def send_json(ws: Any, action: str, **kwargs: Any) -> dict[str, Any]:
    req: dict[str, Any] = {"action": action, **kwargs}
    await ws.send(json.dumps(req, ensure_ascii=False))
    resp = await ws.recv()
    if isinstance(resp, bytes):
        return {"status": "error", "message": "unexpected binary response"}
    return json.loads(resp)


def print_resp(label: str, resp: dict[str, Any]) -> None:
    print(f"[{label}] status={resp.get('status')} message={resp.get('message')}")
    payload = resp.get("payload")
    if payload is not None:
        print(f"[{label}] payload={json.dumps(payload, ensure_ascii=False)}")


async def test_memory_component_load_memory() -> None:
    print(f"[Setup] WS URI: {URI}")
    print(f"[Setup] Circuit file: {CIRCUIT_FILE}")
    print(f"[Setup] TXT path: {TXT_PATH}")
    print(f"[Setup] TXT exists: {os.path.exists(TXT_PATH)}")

    if not os.path.exists(CIRCUIT_FILE):
        raise FileNotFoundError(f"circuit not found: {CIRCUIT_FILE}")

    async with websockets.connect(URI) as ws:
        # 1) Load circuit
        resp = await send_json(ws, "load_circuit", path=CIRCUIT_FILE)
        print_resp("load_circuit", resp)
        if resp.get("status") != "ok":
            raise AssertionError(f"load_circuit failed: {resp}")
        switch_target = "ROM存储器组件电路"
        if switch_target:
            resp = await send_json(ws, "switch_circuit", name=switch_target)
            print_resp("switch_circuit", resp)

        # 2) List subcircuits and switch if needed
        resp = await send_json(ws, "get_io")
        print_resp("get_io", resp)
        circuits = (
            resp.get("payload", []) if isinstance(resp.get("payload"), list) else []
        )

        # 3) Probe component info first (helps identify target/type mismatch)
        resp = await send_json(ws, "get_component_info", target=TARGET)
        print_resp("get_component_info", resp)

        # 4) Reproduce target call from user
        resp = await send_json(ws, "load_memory", target=TARGET, txt_path=TXT_PATH)
        print_resp("load_memory", resp)

        # 5) Optional fallback: if external txt missing, provide a tiny valid sample
        if not os.path.exists(TXT_PATH):
            fallback_txt = os.path.abspath("tests/tmp_memory_sample_v2_raw.txt")
            with open(fallback_txt, "w", encoding="utf-8") as f:
                f.write("v2.0 raw\n00 11 22 33\n")
            resp = await send_json(
                ws, "load_memory", target=TARGET, txt_path=fallback_txt
            )
            print_resp("load_memory(fallback_txt)", resp)

            if os.path.exists(fallback_txt):
                os.remove(fallback_txt)

        await ws.send(
            json.dumps({"action": "get_screenshot", "width": 1600, "height": 900})
        )
        binary_data = await ws.recv()
        if isinstance(binary_data, bytes):
            output_path = "tests/screenshot_memory_component.png"
            with open(output_path, "wb") as f:
                f.write(binary_data)
            print(f"Screenshot saved to: {os.path.abspath(output_path)}")


if __name__ == "__main__":
    asyncio.run(test_memory_component_load_memory())
