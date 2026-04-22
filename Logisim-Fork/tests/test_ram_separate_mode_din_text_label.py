"""集成测试：分离模式下用附近文本 inputDin/Dout 作为别名进行写读验证。"""

import asyncio
import json
import os
from datetime import datetime
from typing import Any

import websockets

PORT = 9924
URI = f"ws://localhost:{PORT}/ws"

CIRCUIT_FILE = os.path.abspath("test_circuits/存储器组件验证实验.circ")
SUBCIRCUIT_NAME = "RAM存储器组件电路（分离模式）"
DIN_VALUE_DEC = "305419896"  # 0x12345678


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


async def test_ram_separate_mode_din_text_label() -> None:
    print(f"[Setup] WS URI: {URI}")
    print(f"[Setup] Circuit file: {CIRCUIT_FILE}")
    print(f"[Setup] Subcircuit: {SUBCIRCUIT_NAME}")

    if not os.path.exists(CIRCUIT_FILE):
        raise FileNotFoundError(f"circuit not found: {CIRCUIT_FILE}")

    async with websockets.connect(URI) as ws:
        resp = await send_json(ws, "load_circuit", path=CIRCUIT_FILE)
        require_ok("load_circuit", resp)

        resp = await send_json(ws, "switch_circuit", name=SUBCIRCUIT_NAME)
        require_ok("switch_circuit", resp)

        resp = await send_json(ws, "get_io")
        require_ok("get_io", resp)
        io_payload = resp.get("payload") or {}
        all_labeled = io_payload.get("all_labeled") or []
        if "inputDin" not in all_labeled:
            raise AssertionError(
                "inputDin text alias not exposed in all_labeled: " + str(io_payload)
            )

        # 1) 预置控制位：仅复位计数器，不清 RAM
        resp = await send_json(ws, "set_value", target="inputrightCLR", value="0")
        require_ok("set_value(inputrightCLR=0)", resp)

        resp = await send_json(ws, "set_value", target="inputSEL", value="0")
        require_ok("set_value(inputSEL=0)", resp)

        resp = await send_json(ws, "set_value", target="inputLD", value="1")
        require_ok("set_value(inputLD=1)", resp)

        # 先把计数器拉回 0，确保后续写入目标地址可控。
        resp = await send_json(ws, "set_value", target="inputleftCLR", value="1")
        require_ok("set_value(inputleftCLR=1)", resp)

        resp = await send_json(ws, "run_tick", tick_count=2)
        require_ok("run_tick(2)-counter-reset-hold", resp)

        resp = await send_json(ws, "set_value", target="inputleftCLR", value="0")
        require_ok("set_value(inputleftCLR=0)", resp)

        # 2) 数据写入：inputDin=0x12345678, sel=0, ld=1, str=1，再打一个完整时钟
        resp = await send_json(ws, "set_value", target="inputDin", value=DIN_VALUE_DEC)
        require_ok("set_value(inputDin=305419896)", resp)

        # 验证写入值可从文本别名 inputDin 正确读回。
        resp = await send_json(
            ws, "check_value", target="inputDin", expected=DIN_VALUE_DEC
        )
        require_ok("check_value(inputDin=305419896)", resp)

        resp = await send_json(ws, "set_value", target="inputSTR", value="1")
        require_ok("set_value(inputSTR=1)", resp)

        resp = await send_json(ws, "run_tick", tick_count=2)
        require_ok("run_tick(2)-write-data", resp)

        # 3) 停止继续写，并仅复位计数器回到地址 0 进行读取校验。
        resp = await send_json(ws, "set_value", target="inputSTR", value="0")
        require_ok("set_value(inputSTR=0)", resp)

        resp = await send_json(ws, "set_value", target="inputleftCLR", value="1")
        require_ok("set_value(inputleftCLR=1)-before-read", resp)

        resp = await send_json(ws, "run_tick", tick_count=2)
        require_ok("run_tick(2)-counter-back-to-zero", resp)

        resp = await send_json(ws, "set_value", target="inputleftCLR", value="0")
        require_ok("set_value(inputleftCLR=0)-before-read", resp)

        # 在失败断言前抓取一帧现场，并输出关键节点值用于定位问题。
        stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        screenshot_path = os.path.abspath(
            f"tests/screenshot_ram_separate_mode_before_check_{stamp}.png"
        )
        await ws.send(
            json.dumps({"action": "get_screenshot", "width": 1600, "height": 900})
        )
        screenshot_data = await ws.recv()
        if isinstance(screenshot_data, bytes):
            with open(screenshot_path, "wb") as f:
                f.write(screenshot_data)
            print(f"[Diag] Screenshot saved: {screenshot_path}")
        else:
            print(f"[Diag] Screenshot failed: {screenshot_data}")

        for target in [
            "Counter",
            "inputDin",
            "Dout",
            "inputSEL",
            "inputLD",
            "inputSTR",
        ]:
            val_resp = await send_json(ws, "get_value", target=target)
            print(
                f"[Diag] {target} = {val_resp.get('payload')} ({val_resp.get('status')})"
            )

        # 4) 验证 Dout 文本别名读取正确
        resp = await send_json(ws, "check_value", target="Dout", expected=DIN_VALUE_DEC)
        require_ok("check_value(Dout=305419896)", resp)


if __name__ == "__main__":
    asyncio.run(test_ram_separate_mode_din_text_label())
