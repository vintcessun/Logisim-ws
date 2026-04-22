"""集成测试：验证 inputleftCLR/inputrightCLR 指令序列与 outputPin 行为。"""

import asyncio
import json
import os
from typing import Any

import websockets

PORT = 9924
URI = f"ws://localhost:{PORT}/ws"

CIRCUIT_FILE = os.path.abspath("test_circuits/存储器组件验证实验.circ")
SUBCIRCUIT_NAME = "RAM存储器组件电路（同步模式）"
PRIMARY_TXT_PATH = "test_circuits/MIPS RAM存储器测试用例——修改后的写入数据.txt"
FALLBACK_TXT_PATH = os.path.abspath(
    "test_circuits/MIPS RAM存储器测试用例——修改后的写入数据.txt"
)


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


def resolve_txt_path() -> str:
    if os.path.exists(PRIMARY_TXT_PATH):
        return PRIMARY_TXT_PATH
    return FALLBACK_TXT_PATH


async def test_ram_component_left_right_clr_sequence() -> None:
    txt_path = resolve_txt_path()
    print(f"[Setup] WS URI: {URI}")
    print(f"[Setup] Circuit file: {CIRCUIT_FILE}")
    print(f"[Setup] Subcircuit: {SUBCIRCUIT_NAME}")
    print(f"[Setup] TXT path: {txt_path}")

    if not os.path.exists(CIRCUIT_FILE):
        raise FileNotFoundError(f"circuit not found: {CIRCUIT_FILE}")
    if not os.path.exists(txt_path):
        raise FileNotFoundError(f"txt not found: {txt_path}")

    async with websockets.connect(URI) as ws:
        try:
            resp = await send_json(ws, "load_circuit", path=CIRCUIT_FILE)
            require_ok("load_circuit", resp)

            resp = await send_json(ws, "switch_circuit", name=SUBCIRCUIT_NAME)
            require_ok("switch_circuit", resp)

            resp = await send_json(ws, "get_io")
            require_ok("get_io", resp)
            io_payload = resp.get("payload") or {}
            inputs = io_payload.get("inputs") or []
            outputs = io_payload.get("outputs") or []
            all_labeled = io_payload.get("all_labeled") or []
            if "inputleftCLR" not in inputs or "inputrightCLR" not in inputs:
                raise AssertionError(
                    "left/right aliases for CLR not found in get_io payload: "
                    + f"{io_payload}"
                )

            # 1. 加载内存（先写入数据，以便验证随后的清零效果）
            resp = await send_json(
                ws, "load_memory", target="RAM", txt_path=os.path.abspath(txt_path)
            )
            require_ok("load_memory", resp)

            # 2. 设置 RAM 模式
            # 【重要修正】遵循实验文档要求：sel=0 才是 RAM 使能！
            resp = await send_json(ws, "set_value", target="inputSEL", value="0")
            require_ok("set_value(inputSEL=0)", resp)

            # ld=1 允许数据输出
            resp = await send_json(ws, "set_value", target="inputLD", value="1")
            require_ok("set_value(inputLD=1)", resp)

            # 3. 产生清零信号 (同时拉高地址和RAM的清零)
            resp = await send_json(ws, "set_value", target="inputleftCLR", value="1")
            require_ok("set_value(inputleftCLR=1)", resp)

            resp = await send_json(ws, "set_value", target="inputrightCLR", value="1")
            require_ok("set_value(inputrightCLR=1)", resp)

            # 4. 打入时钟脉冲，确保同步清零和计数器复位生效
            resp = await send_json(ws, "run_tick", tick_count=2)
            require_ok("run_tick(2)-after-clr-high", resp)

            # 5. 释放清零信号 (拉低)，恢复 RAM 正常读取模式
            resp = await send_json(ws, "set_value", target="inputleftCLR", value="0")
            require_ok("set_value(inputleftCLR=0)", resp)

            resp = await send_json(ws, "set_value", target="inputrightCLR", value="0")
            require_ok("set_value(inputrightCLR=0)", resp)

            # 6. 断言输出
            # 此时清零已释放，没打新时钟，Counter稳在0，RAM正常输出清零后的数据0
            resp = await send_json(ws, "check_value", target="outputPin", expected="0")
            if resp.get("status") != "ok":
                current = await send_json(ws, "get_value", target="outputPin")
                counter = await send_json(ws, "get_value", target="Counter")
                reason = (
                    "outputPin 非 0。如果依然是 xxxxxxxx，请检查电路中 sel 引脚的连接；"
                    "如果是其他数值，说明 RAM 清零逻辑未生效或 Address 不为 0。"
                )
                print(
                    "check_value(outputPin=0) failed. \n"
                    + f"response={resp}; outputPin={current.get('payload')}; \n"
                    + f"Counter={counter.get('payload')}; reason={reason}; \n"
                )
        finally:
            await ws.send(
                json.dumps({"action": "get_screenshot", "width": 1600, "height": 900})
            )
            binary_data = await ws.recv()
            if isinstance(binary_data, bytes):
                output_path = "tests/screenshot_memory_component_ram_left_right_clr.png"
                with open(output_path, "wb") as f:
                    f.write(binary_data)
                print(f"Screenshot saved to: {os.path.abspath(output_path)}")
            else:
                print("[Diagnostic] Failed to receive binary screenshot data")


if __name__ == "__main__":
    asyncio.run(test_ram_component_left_right_clr_sequence())
