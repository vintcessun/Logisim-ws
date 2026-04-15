"""集成测试：验证 cache 电路中的 get_component_info + load_memory(txt_path) API。"""

import asyncio
import json
import os
import tempfile
from typing import Any

import websockets

PORT = 9924
URI = f"ws://localhost:{PORT}/ws"

CIRCUIT_FILE = os.path.abspath("test_circuits/cache 验证实验.circ")
CIRCUIT_NAME = "cache（4路组相联映射）测试电路"

TXT_MAIN_ADDRESS = os.path.abspath(
    "test_circuits/cacahe 测试用例/cacahe 测试用例——主存地址.txt"
)

# 主存内容的示例 v2.0 raw 文件（写入前16字节）
TXT_MAIN_CONTENT_SAMPLE = """v2.0 raw
ab cd ef 42 55 66 77 88
99 aa bb cc dd ee ff 00
"""


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


async def test_get_component_info(ws: Any) -> None:
    print("\n── get_component_info ──────────────────────────────────────────")

    # 主存内容 should be ROM with addrBits=16 / dataBits=8
    resp = await send_json(ws, "get_component_info", target="主存内容")
    check_ok(resp, "get_component_info('主存内容') returns ok")
    info = resp["payload"]
    assert info["type"] == "ROM", f"Expected ROM, got {info['type']}"
    assert info["isMemory"] is True
    assert info["addrBits"] == 16, f"Expected addrBits=16, got {info['addrBits']}"
    assert info["dataBits"] == 8, f"Expected dataBits=8, got {info['dataBits']}"
    assert info["capacity"] == 65536, f"Expected capacity=65536, got {info['capacity']}"
    print(f"  主存内容 → {json.dumps(info, ensure_ascii=False)}")

    # 主存地址 should be ROM with addrBits=10 / dataBits=16
    resp = await send_json(ws, "get_component_info", target="主存地址")
    check_ok(resp, "get_component_info('主存地址') returns ok")
    info = resp["payload"]
    assert info["type"] == "ROM", f"Expected ROM, got {info['type']}"
    assert info["isMemory"] is True
    assert info["addrBits"] == 10, f"Expected addrBits=10, got {info['addrBits']}"
    assert info["dataBits"] == 16, f"Expected dataBits=16, got {info['dataBits']}"
    assert info["capacity"] == 1024, f"Expected capacity=1024, got {info['capacity']}"
    print(f"  主存地址 → {json.dumps(info, ensure_ascii=False)}")


async def test_load_memory_type_guard(ws: Any) -> None:
    print("\n── load_memory type guard ──────────────────────────────────────")

    # "Set" is a Pin – must be rejected
    resp = await send_json(ws, "load_memory", target="Set", txt_path=TXT_MAIN_ADDRESS)
    assert (
        resp.get("status") == "error"
    ), f"[FAIL] Expected error for non-ROM target, got: {resp}"
    assert "not ROM" in resp.get(
        "message", ""
    ), f"[FAIL] Expected 'not ROM' in error message, got: {resp['message']}"
    print("[PASS] load_memory rejects non-ROM component ('Set') with correct message")


async def test_load_memory_boundary_errors(ws: Any) -> None:
    print("\n── load_memory boundary errors ─────────────────────────────────")

    # 非法格式文件（不是 v2.0 raw）
    bad_fd, bad_path = tempfile.mkstemp(prefix="bad_mem_", suffix=".txt")
    os.close(bad_fd)
    with open(bad_path, "w", encoding="utf-8") as f:
        f.write("not-a-valid-hex-format\n1 2 3\n")

    try:
        resp = await send_json(ws, "load_memory", target="主存内容", txt_path=bad_path)
        assert (
            resp.get("status") == "error"
        ), f"[FAIL] Expected error for invalid txt format, got: {resp}"
        assert "v2.0 raw" in resp.get("message", "") or "Failed" in resp.get(
            "message", ""
        ), f"[FAIL] Expected format-related error message, got: {resp.get('message')}"
        print("[PASS] load_memory rejects malformed txt format")
    finally:
        if os.path.exists(bad_path):
            os.remove(bad_path)

    # Empty contents must be rejected
    resp = await send_json(ws, "load_memory", target="主存内容", contents={})
    assert (
        resp.get("status") == "error"
    ), f"[FAIL] Expected error for empty contents, got: {resp}"
    print("[PASS] load_memory rejects empty contents map")


async def test_load_memory_request_errors(ws: Any) -> None:
    print("\n── load_memory request errors ──────────────────────────────────")

    # 缺少 target
    resp = await send_json(ws, "load_memory", txt_path=TXT_MAIN_ADDRESS)
    assert (
        resp.get("status") == "error"
    ), f"[FAIL] Expected error when target is missing, got: {resp}"
    assert "target" in resp.get(
        "message", ""
    ), f"[FAIL] Expected target-related message, got: {resp.get('message')}"
    print("[PASS] load_memory rejects missing target")

    # target 不存在
    resp = await send_json(
        ws, "load_memory", target="不存在的标签", txt_path=TXT_MAIN_ADDRESS
    )
    assert (
        resp.get("status") == "error"
    ), f"[FAIL] Expected error for non-existent target, got: {resp}"
    assert "Component not found" in resp.get(
        "message", ""
    ), f"[FAIL] Expected component-not-found message, got: {resp.get('message')}"
    print("[PASS] load_memory rejects unknown target label")

    # txt_path 文件不存在
    missing_path = os.path.abspath("test_circuits/cacahe 测试用例/不存在.txt")
    resp = await send_json(ws, "load_memory", target="主存地址", txt_path=missing_path)
    assert (
        resp.get("status") == "error"
    ), f"[FAIL] Expected error for missing txt file, got: {resp}"
    assert "txt_path" in resp.get("message", "") or "exist" in resp.get(
        "message", ""
    ), f"[FAIL] Expected txt_path related message, got: {resp.get('message')}"
    print("[PASS] load_memory rejects non-existent txt_path")

    # get_component_info 查询不存在标签
    resp = await send_json(ws, "get_component_info", target="不存在的标签")
    assert (
        resp.get("status") == "error"
    ), f"[FAIL] Expected error for get_component_info unknown label, got: {resp}"
    assert "Component not found" in resp.get(
        "message", ""
    ), f"[FAIL] Expected component-not-found message, got: {resp.get('message')}"
    print("[PASS] get_component_info rejects unknown target label")


async def test_load_memory_success(ws: Any) -> None:
    print("\n── load_memory success ─────────────────────────────────────────")

    assert os.path.exists(TXT_MAIN_ADDRESS), f"txt not found: {TXT_MAIN_ADDRESS}"

    # Load 主存地址 from existing v2.0 raw txt
    resp = await send_json(
        ws, "load_memory", target="主存地址", txt_path=TXT_MAIN_ADDRESS
    )
    check_ok(resp, "load_memory('主存地址', txt_path) succeeds")

    # Create temp v2.0 raw txt for 主存内容
    fd, txt_main_content = tempfile.mkstemp(prefix="main_content_", suffix=".txt")
    os.close(fd)
    with open(txt_main_content, "w", encoding="utf-8") as f:
        f.write(TXT_MAIN_CONTENT_SAMPLE)

    try:
        resp = await send_json(
            ws, "load_memory", target="主存内容", txt_path=txt_main_content
        )
        check_ok(resp, "load_memory('主存内容', txt_path) succeeds")
    finally:
        if os.path.exists(txt_main_content):
            os.remove(txt_main_content)


async def test_cache_memory_load():
    print(f"[Setup] Using circuit: {CIRCUIT_FILE}")
    print(f"[Setup] Connecting to {URI} ...")

    async with websockets.connect(URI) as ws:
        # 1. Load circuit
        resp = await send_json(ws, "load_circuit", path=CIRCUIT_FILE)
        check_ok(resp, "load_circuit")

        # 2. Switch to target subcircuit
        resp = await send_json(ws, "switch_circuit", name=CIRCUIT_NAME)
        check_ok(resp, f"switch_circuit('{CIRCUIT_NAME}')")

        # 3. Confirm I/O
        resp = await send_json(ws, "get_io")
        print(
            f"\n[Info] I/O: {json.dumps(resp.get('payload', {}), ensure_ascii=False)}"
        )

        # 4. Type detection tests
        await test_get_component_info(ws)

        # 5. Type guard: reject non-ROM target
        await test_load_memory_type_guard(ws)

        # 6. Boundary / malformed input errors
        await test_load_memory_boundary_errors(ws)

        # 6.1 Request validation errors
        await test_load_memory_request_errors(ws)

        # 7. Successful memory loads
        await test_load_memory_success(ws)

    print("\n✅ All cache memory load tests passed.")


if __name__ == "__main__":
    asyncio.run(test_cache_memory_load())
