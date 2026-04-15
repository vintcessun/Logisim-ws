import asyncio
import io
import json
import os

from PIL import Image
import websockets


PORT = 9924
URI = f"ws://localhost:{PORT}/ws"


async def send_json(ws, action, **kwargs):
    req = {"action": action, **kwargs}
    await ws.send(json.dumps(req, ensure_ascii=False))
    resp = await ws.recv()
    if isinstance(resp, bytes):
        return resp
    return json.loads(resp)


def to_signed(value, bits):
    sign_bit = 1 << (bits - 1)
    return value - (1 << bits) if value & sign_bit else value


def normalize_hex(value, bits):
    width = bits // 4
    return f"0x{value & ((1 << bits) - 1):0{width}x}"


async def save_screenshot(ws, output_path):
    binary_data = await send_json(ws, "get_screenshot", width=1920, height=1080)
    if not isinstance(binary_data, bytes):
        raise RuntimeError(f"Expected binary screenshot data, got: {binary_data}")

    image = Image.open(io.BytesIO(binary_data))
    image.save(output_path)
    return image.size


async def test_signed_multiplier_diagnosis():
    circ_path = os.path.abspath("test_circuits/原码和补码一位乘法器.circ")
    circuit_name = "8位补码一位乘法器（复杂版）"
    output_path = os.path.abspath("tests/signed_multiplier_diagnosis.png")

    x_raw = 0x80
    y_raw = 0x02
    expected_unsigned = (x_raw * y_raw) & 0xFFFF
    expected_signed = (to_signed(x_raw, 8) * to_signed(y_raw, 8)) & 0xFFFF

    print(f"[Emulator] 自动加载电路: {circ_path}")
    async with websockets.connect(URI) as ws:
        load_resp = await send_json(ws, "load_circuit", path=circ_path)
        print(f"[Emulator] 电路加载响应: {load_resp}")

        print(f"[Verification] 正在切换到电路: {circuit_name}")
        switch_resp = await send_json(ws, "switch_circuit", name=circuit_name)
        print(f"[Verification] 切换电路响应: {switch_resp}")

        io_resp = await send_json(ws, "get_io")
        print(f"[Verification] 当前 I/O: {json.dumps(io_resp, ensure_ascii=False)}")

        sequence = [
            {"action": "set_value", "target": "X", "value": "0x80"},
            {"action": "set_value", "target": "Y", "value": "0x02"},
            {"action": "set_value", "target": "复位", "value": "1"},
            {"action": "set_value", "target": "时钟", "value": "0"},
            {"action": "set_value", "target": "复位", "value": "0"},
        ]

        print("[Verification] 开始执行 API 指令序列...")
        for command in sequence:
            print(f"[Run API] >> {command}")
            response = await send_json(
                ws, command["action"], target=command["target"], value=command["value"]
            )
            print(f"[Run API] << {response}")

        tick_command = {
            "action": "tick_until",
            "target": "END",
            "expected": "1",
            "max": 100,
            "clock": "时钟",
        }
        print(f"[Run API] >> {tick_command}")
        tick_resp = await send_json(
            ws,
            "tick_until",
            target="END",
            expected="1",
            max=100,
            clock="时钟",
        )
        print(f"[Run API] << {tick_resp}")

        product_resp = await send_json(ws, "get_value", target="乘积")
        end_resp = await send_json(ws, "get_value", target="END")
        actual_hex = product_resp.get("payload")
        actual_value = int(actual_hex, 16)

        print(f"[Readback] END = {end_resp.get('payload')}")
        print(f"[Readback] 乘积 = {actual_hex}")
        print(
            "[Diagnosis] X=0x80 作为 8 位数时: "
            f"unsigned={x_raw}, signed={to_signed(x_raw, 8)}"
        )
        print(
            "[Diagnosis] Y=0x02 作为 8 位数时: "
            f"unsigned={y_raw}, signed={to_signed(y_raw, 8)}"
        )
        print(f"[Diagnosis] 无符号期望乘积: {normalize_hex(expected_unsigned, 16)}")
        print(f"[Diagnosis] 补码有符号期望乘积: {normalize_hex(expected_signed, 16)}")
        print(
            f"[Diagnosis] 实际乘积: {normalize_hex(actual_value, 16)} "
            f"(signed={to_signed(actual_value, 16)})"
        )

        screenshot_size = await save_screenshot(ws, output_path)
        print(
            f"[Verification] 截图已保存: {output_path} "
            f"尺寸={screenshot_size[0]}x{screenshot_size[1]}"
        )

        if actual_value == expected_signed:
            print(
                "[Conclusion] 实际结果与补码乘法预期一致，"
                "更可能是原先期望 0x0100 写错，不是仿真器接口比较错误。"
            )
        elif actual_value == expected_unsigned:
            print(
                "[Conclusion] 实际结果与无符号乘法一致，"
                "需要检查该子电路是否并非真正的补码乘法实现。"
            )
        else:
            print(
                "[Conclusion] 实际结果既不符合补码乘法，也不符合无符号乘法，"
                "需要结合截图继续检查电路实现。"
            )

        check_resp = await send_json(
            ws, "check_value", target="乘积", expected="0x0100"
        )
        print(
            f"[Run API] >> {{'action': 'check_value', 'target': '乘积', 'expected': '0x0100'}}"
        )
        print(f"[Run API] << {check_resp}")


if __name__ == "__main__":
    asyncio.run(test_signed_multiplier_diagnosis())
