import asyncio
import websockets
import json
import os

PORT = 9924
URI = f"ws://localhost:{PORT}/ws"


async def send_json(ws, action, **kwargs):
    req = {"action": action, **kwargs}
    await ws.send(json.dumps(req))
    resp = await ws.recv()
    return json.loads(resp)


async def test_parsing():
    print(f"Connecting to {URI}...")
    async with websockets.connect(URI) as ws:
        # 1. 加载电路文件
        circ_path = os.path.abspath("test_circuits/先行进位加法器.circ")
        print(f"Loading circuit: {circ_path}")
        await send_json(ws, "load_circuit", path=circ_path)

        # 2. 获取并切换到包含 X 的子电路
        resp = await send_json(ws, "get_circuits")
        circs = resp.get("payload", [])
        target_circ = "16位快速加法器（组内并行、组间串行）（1）"
        if target_circ not in circs:
            for c in circs:
                if "16位" in c:
                    target_circ = c
                    break
        print(f"Switching to circuit: {target_circ}")
        await send_json(ws, "switch_circuit", name=target_circ)

        # 3. 检查 IO
        resp = await send_json(ws, "get_io")
        io = resp.get("payload", {})
        print(f"Available Inputs: {io.get('inputs')}")

        target_pin = "X"
        if target_pin not in io.get("all_labeled", []):
            if io.get("inputs"):
                target_pin = io.get("inputs")[0]
            else:
                print("❌ No labeled components found to test.")
                return

        test_cases = [
            (target_pin, "0x1A", "0x001a"),  # Hex
            (target_pin, "0b1010", "0x000a"),  # Binary (10)
            (target_pin, "012", "0x000a"),  # Octal (10)
            (target_pin, "123", "0x007b"),  # Decimal (123 = 0x7B)
            (target_pin, "0", "0x0000"),  # Zero
            (target_pin, "x", "unknown"),  # Unknown
        ]

        for pin, input_val, expected_hex in test_cases:
            print(f"Testing input: '{input_val}' for pin '{pin}'...")
            request_resp = await send_json(ws, "set_value", target=pin, value=input_val)
            resp = await send_json(ws, "get_value", target=pin)
            actual_hex = resp.get("payload", "").lower()

            if actual_hex == expected_hex.lower() or (
                expected_hex == "unknown" and "unknown" in actual_hex
            ):
                print(f"  ✅ Result: {actual_hex} (Match)")
            elif request_resp["status"] == "error":
                print(f"  ✅ Request Error: {request_resp}")
            else:
                print(f"  ❌ Result: {actual_hex} (Expected: {expected_hex})")


if __name__ == "__main__":
    asyncio.run(test_parsing())
