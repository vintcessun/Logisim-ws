"""集成测试：全相联映射测试电路，执行宏命令并截图。"""

import asyncio
import json
import os
from typing import Any

import websockets

PORT = 9924
URI = f"ws://localhost:{PORT}/ws"

CIRCUIT_FILE = os.path.abspath("test_circuits/cache 验证实验.circ")
CIRCUIT_NAME = "cache（全相联映射）测试电路"
TARGET = "时钟周期数"
SCREENSHOT_PATH = os.path.abspath("tests/cache_full_assoc_macro.png")


async def send_json(ws: Any, action: str, **kwargs: Any) -> dict[str, Any]:
    req: dict[str, Any] = {"action": action, **kwargs}
    await ws.send(json.dumps(req, ensure_ascii=False))
    resp = await ws.recv()
    if isinstance(resp, bytes):
        raise AssertionError(f"Unexpected binary response for action '{action}'")
    return json.loads(resp)


def check_ok(resp: dict[str, Any], context: str) -> None:
    if resp.get("status") != "ok":
        raise AssertionError(f"[FAIL] {context}: expected status=ok, got {resp}")
    print(f"[PASS] {context}")


async def save_screenshot(ws: Any, output_path: str) -> None:
    await ws.send(json.dumps({"action": "get_screenshot"}, ensure_ascii=False))
    binary = await ws.recv()
    if not isinstance(binary, bytes):
        raise AssertionError(
            f"[FAIL] get_screenshot should return bytes, got: {binary}"
        )
    with open(output_path, "wb") as f:
        f.write(binary)


async def test_cache_full_assoc_macro() -> None:
    print(f"[Setup] Using circuit: {CIRCUIT_FILE}")
    print(f"[Setup] Connecting to {URI} ...")

    async with websockets.connect(URI) as ws:
        # 1. Load .circ
        resp = await send_json(ws, "load_circuit", path=CIRCUIT_FILE)
        check_ok(resp, "load_circuit")

        # 2. Switch to full-associative test circuit
        resp = await send_json(ws, "switch_circuit", name=CIRCUIT_NAME)
        check_ok(resp, f"switch_circuit('{CIRCUIT_NAME}')")

        # 3. Run macro command
        # NOTE: expected is intentionally omitted per requirement.
        resp = await send_json(
            ws,
            "run_until_stable_then_tick",
            target=TARGET,
            timeout_second=10,
            k=100,
        )
        check_ok(
            resp,
            "run_until_stable_then_tick(target='时钟周期数', timeout_second=10, k=100)",
        )

        payload = resp.get("payload", {})
        print(f"[Info] Macro payload: {json.dumps(payload, ensure_ascii=False)}")

        # 4. Save screenshot under tests/
        await save_screenshot(ws, SCREENSHOT_PATH)
        print(f"[PASS] Screenshot saved to: {SCREENSHOT_PATH}")

    print("\n✅ Full-associative macro simulation test passed.")


if __name__ == "__main__":
    asyncio.run(test_cache_full_assoc_macro())
