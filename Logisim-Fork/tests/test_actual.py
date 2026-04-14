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
    data = json.loads(resp)
    if data["status"] == "error":
        raise ValueError("The request " + action + " failed: " + data["message"])
    return data

async def test_serial_adder():
    print(f"\n--- Starting 8-bit Serial Adder Test ---")
    async with websockets.connect(URI) as ws:
        # 1. 加载加法器电路
        circ_path = os.path.abspath("test_circuits/串行加法器.circ")
        print(f"[1] Loading adder circuit: {circ_path}")
        await send_json(ws, "load_circuit", path=circ_path)

        # 2. 切换到 8位串行加法器（1）
        target_name = "8位串行加法器（1）"
        print(f"[2] Switching to: {target_name}")
        await send_json(ws, "switch_circuit", name=target_name)

        # 3. 设置输入 Xi=15, Yi=12, Cin=0
        # 15 + 12 = 27 (0x1B)
        xi_val, yi_val, cin_val = 15, 12, 0
        print(f"[3] Setting inputs: X={xi_val}, Y={yi_val}, Cin={cin_val}")
        await send_json(ws, "set_value", target="X", value=str(xi_val))
        await send_json(ws, "set_value", target="Y", value=str(yi_val))
        await send_json(ws, "set_value", target="Cin", value=str(cin_val))

        await ws.send(json.dumps({"action": "get_screenshot", "width": 1920, "height": 1080}))
        binary_data = await ws.recv()
        if isinstance(binary_data, bytes):
            output_path = "tests/screenshot_actual.png"
            with open(output_path, "wb") as f:
                f.write(binary_data)
            print(f"Screenshot saved to: {os.path.abspath(output_path)}")
        else:
            print("Failed to receive binary image data.")

        # 4. 读取结果 Si 和 Cout
        print("[4] Fetching results...")
        si_resp = await send_json(ws, "get_value", target="S")
        cout_resp = await send_json(ws, "get_value", target="Cout")
            
        actual_si = si_resp.get("payload")
        actual_cout = cout_resp.get("payload")
            
        print(f"Result Si:   {actual_si}")
        print(f"Result Cout: {actual_cout}")
            
        # 验证 (15 + 12 = 27, hex 1b)
        # 对于 8 位加法器，Si 应该是 1b, Cout 应该是 0
        if actual_si and int(actual_si, 16) == 27:
            print("✅ Serial Adder Test PASSED!")
        else:
            print("❌ Serial Adder Test FAILED (Expected Si=001b)")

async def main():
    await test_serial_adder()

if __name__ == "__main__":
    asyncio.run(main())
