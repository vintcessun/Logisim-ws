"""集成测试：验证 load_memory(target="RAM") 在“RAM存储器组件电路（异步模式）”中的行为。"""

import asyncio
import json
import os
from typing import Any

import websockets

PORT = 9924
URI = f"ws://localhost:{PORT}/ws"

CIRCUIT_FILE = os.path.abspath("test_circuits/存储器组件验证实验.circ")
SUBCIRCUIT_NAME = "RAM存储器组件电路（异步模式）"
TXT_PATH = os.path.abspath("test_circuits/MIPS RAM存储器测试用例——修改后的写入数据.txt")
TARGET = "RAM"


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


def print_resp(label: str, resp: dict[str, Any]) -> None:
    print(f"[{label}] status={resp.get('status')} message={resp.get('message')}")
    payload = resp.get("payload")
    if payload is not None:
        print(f"[{label}] payload={json.dumps(payload, ensure_ascii=False)}")


async def test_ram_component_load_memory_async() -> None:
    print(f"[Setup] WS URI: {URI}")
    print(f"[Setup] Circuit file: {CIRCUIT_FILE}")
    print(f"[Setup] Subcircuit: {SUBCIRCUIT_NAME}")
    print(f"[Setup] TXT path: {TXT_PATH}")
    print(f"[Setup] TXT exists: {os.path.exists(TXT_PATH)}")

    if not os.path.exists(CIRCUIT_FILE):
        raise FileNotFoundError(f"circuit not found: {CIRCUIT_FILE}")
    if not os.path.exists(TXT_PATH):
        raise FileNotFoundError(f"txt not found: {TXT_PATH}")

    async with websockets.connect(URI) as ws:
        # 1) 加载总电路
        resp = await send_json(ws, "load_circuit", path=CIRCUIT_FILE)
        print_resp("load_circuit", resp)
        if resp.get("status") != "ok":
            raise AssertionError(f"load_circuit failed: {resp}")

        # 2) 切换到 RAM 异步子电路
        resp = await send_json(ws, "switch_circuit", name=SUBCIRCUIT_NAME)
        print_resp("switch_circuit", resp)
        if resp.get("status") != "ok":
            raise AssertionError(f"switch_circuit failed: {resp}")

        # 3) 获取 IO，便于诊断前缀命名是否正常
        resp = await send_json(ws, "get_io")
        print_resp("get_io", resp)

        # 4) RAM 信息探测（无 label 时依赖唯一工厂名 RAM）
        resp = await send_json(ws, "get_component_info", target=TARGET)
        print_resp("get_component_info", resp)
        if resp.get("status") != "ok":
            raise AssertionError(f"get_component_info failed: {resp}")

        # 5) 用 txt 导入 RAM 内容
        resp = await send_json(ws, "load_memory", target=TARGET, txt_path=TXT_PATH)
        print_resp("load_memory", resp)
        if resp.get("status") != "ok":
            raise AssertionError(f"load_memory failed: {resp}")

        # 6) 截图确认
        # 注意：异步 RAM 在某些控制组合下会在传播过程中写回总线值，
        # 因此这里不做额外 tick，直接截图验证加载后的内存视图。
        await ws.send(
            json.dumps({"action": "get_screenshot", "width": 1600, "height": 900})
        )
        binary_data = await ws.recv()
        if not isinstance(binary_data, bytes):
            raise AssertionError("Failed to receive binary screenshot data")

        output_path = "tests/screenshot_memory_component_ram_async.png"
        with open(output_path, "wb") as f:
            f.write(binary_data)
        print(f"Screenshot saved to: {os.path.abspath(output_path)}")


if __name__ == "__main__":
    asyncio.run(test_ram_component_load_memory_async())
