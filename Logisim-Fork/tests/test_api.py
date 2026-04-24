import asyncio
import websockets
import json
import os
import sys

# 设置为当前运行服务器的端口
PORT = 9924
URI = f"ws://localhost:{PORT}/ws"


async def send_json(ws, action, **kwargs):
    req = {"action": action, **kwargs}
    await ws.send(json.dumps(req))
    resp = await ws.recv()
    try:
        data = json.loads(resp)
        return data
    except:
        return resp


async def test_full_flow():
    print(f"Connecting to {URI}...")
    try:
        async with websockets.connect(URI) as ws:
            print("Connected.")

            # 1. 加载电路文件
            circ_path = os.path.abspath("test_circuits/先行进位加法器.circ")
            print(f"\n[1] Loading circuit: {circ_path}")
            resp = await send_json(ws, "load_circuit", path=circ_path)
            print("Response:", resp["status"])

            # 2. 获取所有子电路
            print("\n[2] Getting all sub-circuits...")
            resp = await send_json(ws, "get_circuits")
            circuits = resp.get("payload", [])
            print("Available circuits:", circuits)

            # 3. 切换到目标 16 位快速加法器
            target_name = "16位快速加法器（组内并行、组间串行）（1）"
            # 兼容性检查：如果名称不完全匹配（如编码问题），尝试查找包含 16位的第一个电路
            if target_name not in circuits:
                for c in circuits:
                    if "16位" in c and "1" in c:
                        target_name = c
                        break

            print(f"\n[3] Switching to circuit: {target_name}")
            resp = await send_json(ws, "switch_circuit", name=target_name)
            print("Response:", resp["status"])

            # 4. 获取 IO 信息
            print("\n[4] Fetching I/O labels...")
            resp = await send_json(ws, "get_io")
            io = resp.get("payload", {})
            print("Inputs:", io.get("inputs"))
            print("Outputs:", io.get("outputs"))

            # 4.1 新增 API 冒烟：组件枚举与按规则解析
            print("\n[4.1] Listing components (is_memory=true)...")
            resp = await send_json(ws, "list_components", is_memory=True)
            print("Response:", resp.get("status"))
            memory_components = (
                resp.get("payload", []) if isinstance(resp, dict) else []
            )
            print("Memory component count:", len(memory_components))

            print("\n[4.2] Resolving component by target='RAM'...")
            resp = await send_json(ws, "resolve_component", target="RAM")
            print("Response:", resp.get("status"))
            print("Payload:", resp.get("payload"))

            # 5. 设置输入值 X, Y, C0
            # X = 0x1234, Y = 0xABCD, C0 = 1
            print("\n[5] Setting input values (X=0x1234, Y=0xABCD, C0=1)...")
            await send_json(ws, "set_value", target="X", value="0x1234")
            await send_json(ws, "set_value", target="Y", value="0xABCD")
            await send_json(ws, "set_value", target="C0", value="1")
            print("Values set.")

            # 6. 获取输出结果 S, C16, C15
            print("\n[6] Getting output results...")
            s_val = await send_json(ws, "get_value", target="S")
            c16_val = await send_json(ws, "get_value", target="C16")
            c15_val = await send_json(ws, "get_value", target="C15")

            print(
                f"Result S:   {s_val.get('payload') if isinstance(s_val, dict) else s_val}"
            )
            print(
                f"Result C16: {c16_val.get('payload') if isinstance(c16_val, dict) else c16_val}"
            )
            print(
                f"Result C15: {c15_val.get('payload') if isinstance(c15_val, dict) else c15_val}"
            )

            # 7. 截图并保存
            print("\n[7] Taking screenshot (1920x1080)...")
            await ws.send(
                json.dumps({"action": "get_screenshot", "width": 1920, "height": 1080})
            )
            binary_data = await ws.recv()
            if isinstance(binary_data, bytes):
                output_path = "tests/screenshot_adder_result.png"
                with open(output_path, "wb") as f:
                    f.write(binary_data)
                print(f"Screenshot saved to: {os.path.abspath(output_path)}")
            else:
                print("Failed to receive binary image data.")

            print("\nFull API Test Sequence Completed Successfully.")

    except Exception as e:
        print(f"An error occurred: {e}")


async def test_multiplier_flow():
    print(f"\n--- Starting Multiplier Test ---")
    try:
        async with websockets.connect(URI) as ws:
            # 1. 加载乘法器电路
            circ_path = os.path.abspath("test_circuits/原码和补码一位乘法器.circ")
            print(f"[1] Loading multiplier circuit: {circ_path}")
            await send_json(ws, "load_circuit", path=circ_path)

            # 2. 切换到 8位补码一位乘法器（复杂版）
            target_name = "8位补码一位乘法器（复杂版）"
            print(f"[2] Switching to: {target_name}")
            await send_json(ws, "switch_circuit", name=target_name)

            print(" [2] Fetching I/O labels...")
            resp = await send_json(ws, "get_io")
            io = resp.get("payload", {})
            print("Inputs:", io.get("inputs"))
            print("Outputs:", io.get("outputs"))

            # 3. 设置输入 X=7 (0x07), Y=3 (0x03) -> Result = 21 (0x0015)
            x_val, y_val = 7, 3
            print(f"[3] Setting inputs: X={x_val}, Y={y_val}")
            await send_json(ws, "set_value", target="X", value=str(x_val))
            await send_json(ws, "set_value", target="Y", value=str(y_val))

            # 4. 复位操作
            print("[4] Pulsing Reset (复位)...")
            await send_json(ws, "set_value", target="复位", value="1")
            await send_json(ws, "set_value", target="复位", value="0")

            # 5. 自动时钟直到 END 亮起
            # 目标: END=1, 时钟: 时钟 (Button label)
            print("[5] Ticking until END=1 using '时钟' as clock...")
            resp = await send_json(
                ws, "tick_until", target="END", expected="0x1", clock="时钟", max=30
            )
            if resp.get("status") == "ok":
                print(f"Simulation completed in {resp.get('ticks')} clock cycles.")
            else:
                print("Simulation failed or timed out:", resp.get("message"))

            # 6. 读取结果并验证
            print("[6] Verifying result '乘积'...")
            prod_resp = await send_json(ws, "get_value", target="乘积")
            actual_hex = prod_resp.get("payload")
            actual_val = int(actual_hex, 16) if "unknown" not in actual_hex else 0
            expected_val = (x_val * y_val) & 0xFFFF

            print(f"Expected: {expected_val} (0x{expected_val:04x})")
            print(f"Actual:   {actual_val} ({actual_hex})")

            if actual_val == expected_val:
                print("✅ Multiplier Test PASSED!")
            else:
                print("❌ Multiplier Test FAILED!")

            # 7. 截图
            print("[7] Taking multiplier result screenshot...")
            await ws.send(
                json.dumps({"action": "get_screenshot", "width": 1600, "height": 900})
            )
            binary_data = await ws.recv()
            if isinstance(binary_data, bytes):
                output_path = "tests/screenshot_multiplier_result.png"
                with open(output_path, "wb") as f:
                    f.write(binary_data)
                print(f"Screenshot saved to: {os.path.abspath(output_path)}")

    except Exception as e:
        print(f"An error occurred in multiplier test: {e}")


async def main():
    # 依次运行两个测试
    await test_full_flow()
    await test_multiplier_flow()


if __name__ == "__main__":
    asyncio.run(main())
