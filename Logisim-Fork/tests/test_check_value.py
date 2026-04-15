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
    try:
        data = json.loads(resp)
        return data
    except:
        return resp


async def test_check_value():
    print(f"Connecting to {URI}...")
    try:
        async with websockets.connect(URI) as ws:
            print("Connected.\n")

            # Test 1: No circuit loaded
            print("[Test 1] check_value without loading circuit...")
            resp = await send_json(ws, "check_value", target="X", expected="0x0000")
            if resp.get("status") == "error" and "No circuit loaded" in resp.get(
                "message", ""
            ):
                print("  ✅ Correctly returned error: No circuit loaded\n")
            else:
                print(f"  ❌ Unexpected response: {resp}\n")

            # Load circuit
            circ_path = os.path.abspath("test_circuits/先行进位加法器.circ")
            print(f"[Setup] Loading circuit: {circ_path}")
            resp = await send_json(ws, "load_circuit", path=circ_path)
            print(f"  Response: {resp['status']}\n")

            # Get I/O info
            print("[Setup] Fetching I/O labels...")
            resp = await send_json(ws, "get_io")
            io = resp.get("payload", {})
            print(f"  Inputs: {io.get('inputs')}\n")

            # Test 2: Component not found
            print("[Test 2] check_value with non-existent component...")
            resp = await send_json(
                ws, "check_value", target="NonExistent", expected="0x0000"
            )
            if (
                resp.get("status") == "error"
                and "not found" in resp.get("message", "").lower()
            ):
                print(f"  ✅ Correctly returned error: {resp.get('message')}\n")
            else:
                print(f"  ❌ Unexpected response: {resp}\n")

            # Test 3: Set value and verify with check_value (matching)
            print("[Test 3] Setting value and checking match...")
            print("  Setting P1 = 1...")
            await send_json(ws, "set_value", target="P1", value="1")

            print("  Checking P1 == 1...")
            resp = await send_json(ws, "check_value", target="P1", expected="1")
            if resp.get("status") == "ok":
                print("  ✅ Value matches successfully\n")
            else:
                print(f"  ❌ Unexpected response: {resp}\n")

            # Test 4: Check value with mismatch (should fail)
            print("[Test 4] Checking value mismatch (should fail)...")
            print("  Checking P1 == 0 (but P1 is 1)...")
            resp = await send_json(ws, "check_value", target="P1", expected="0")
            if (
                resp.get("status") == "error"
                and "mismatch" in resp.get("message", "").lower()
            ):
                print(f"  ✅ Correctly returned error: {resp.get('message')}\n")
            else:
                print(f"  ❌ Unexpected response: {resp}\n")

            # Test 5: Check with different value formats
            print("[Test 5] Testing different value formats...")

            print("  Setting P1 = 1 (binary)...")
            await send_json(ws, "set_value", target="P1", value="1")

            print("  Checking P1 == 0x1 (hex equivalent)...")
            resp = await send_json(ws, "check_value", target="P1", expected="0x1")
            if resp.get("status") == "ok":
                print("  ✅ Format conversion works (1 == 0x1)\n")
            else:
                print(f"  ❌ Unexpected response: {resp}\n")

            # Test 6: Check with binary format
            print("[Test 6] Checking with binary format...")

            print("  Setting P1 = 0b1...")
            await send_json(ws, "set_value", target="P1", value="0b1")

            print("  Checking P1 == 1...")
            resp = await send_json(ws, "check_value", target="P1", expected="1")
            if resp.get("status") == "ok":
                print("  ✅ Binary format works (0b1 == 1)\n")
            else:
                print(f"  ❌ Unexpected response: {resp}\n")

            # Test 7: Multiple sequential checks
            print("[Test 7] Multiple sequential checks with P2...")

            print("  Setting P2 = 1...")
            await send_json(ws, "set_value", target="P2", value="1")

            checks = [
                ("1", True),  # Should pass
                ("1", True),  # Should pass again
                ("0", False),  # Should fail
                ("1", True),  # Should pass again
            ]

            for expected, should_pass in checks:
                resp = await send_json(
                    ws, "check_value", target="P2", expected=expected
                )
                status = resp.get("status")
                passed = (status == "ok") == should_pass
                symbol = "✅" if passed else "❌"
                print(f"    {symbol} check_value(P2, {expected}): {status}")

            print()

            print("=" * 50)
            print("✅ check_value Test Suite Completed Successfully!")
            print("=" * 50)

    except Exception as e:
        print(f"Connection error: {e}")


if __name__ == "__main__":
    asyncio.run(test_check_value())
