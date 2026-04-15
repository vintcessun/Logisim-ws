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


async def test_robust_value_comparison():
    print(f"Connecting to {URI}...")
    try:
        async with websockets.connect(URI) as ws:
            print("Connected.\n")

            # Load circuit
            circ_path = os.path.abspath("test_circuits/先行进位加法器.circ")
            print(f"[Setup] Loading circuit: {circ_path}")
            resp = await send_json(ws, "load_circuit", path=circ_path)
            print(f"  Response: {resp['status']}\n")

            # Test: Different format inputs that represent the same value
            print("=" * 60)
            print("Testing Robust Value Comparison")
            print("=" * 60)

            test_cases = [
                (
                    "P1",
                    [
                        ("1", "0x1", "decimal vs hex with prefix"),
                        ("1", "1", "decimal vs decimal"),
                        ("0x1", "1", "hex with prefix vs decimal"),
                        ("0b1", "1", "binary vs decimal"),
                        ("0b1", "0x1", "binary vs hex"),
                    ],
                ),
                (
                    "P2",
                    [
                        ("10", "0xa", "decimal 10 vs hex a"),
                        ("10", "0b1010", "decimal 10 vs binary 1010"),
                        ("0xa", "0b1010", "hex a vs binary 1010"),
                        ("0xf", "15", "hex f vs decimal 15"),
                    ],
                ),
            ]

            for pin, comparisons in test_cases:
                print(f"\n[Testing Pin: {pin}]")

                for set_val, check_val, description in comparisons:
                    # Set the value
                    await send_json(ws, "set_value", target=pin, value=set_val)

                    # Get the actual value
                    get_resp = await send_json(ws, "get_value", target=pin)
                    actual_val = get_resp.get("payload")

                    # Check with different format
                    check_resp = await send_json(
                        ws, "check_value", target=pin, expected=check_val
                    )

                    if check_resp.get("status") == "ok":
                        print(f"  ✅ {description}")
                        print(
                            f"     Set: {set_val} → Got: {actual_val} → Check: {check_val}"
                        )
                    else:
                        print(f"  ❌ {description}")
                        print(
                            f"     Set: {set_val} → Got: {actual_val} → Check: {check_val}"
                        )
                        print(f"     Error: {check_resp.get('message')}")

            # Edge case: values from getValue() output vs user input
            print(f"\n[Testing Edge Case: getValue() output format]")

            print("  Setting P1 = 60...")
            await send_json(ws, "set_value", target="P1", value="60")

            # Get value returns hex format (like "003c")
            get_resp = await send_json(ws, "get_value", target="P1")
            actual_hex = get_resp.get("payload")
            print(f"  Got value (from getValue): {actual_hex}")

            # Now check with decimal
            check_resp = await send_json(ws, "check_value", target="P1", expected="60")
            if check_resp.get("status") == "ok":
                print(f"  ✅ Decimal comparison works: expect 60, got {actual_hex}")
            else:
                print(f"  ❌ Decimal comparison failed: {check_resp.get('message')}")

            # Check with hex format from getValue output
            check_resp2 = await send_json(
                ws, "check_value", target="P1", expected=actual_hex
            )
            if check_resp2.get("status") == "ok":
                print(
                    f"  ✅ Hex format comparison works: expect {actual_hex}, got {actual_hex}"
                )
            else:
                print(
                    f"  ❌ Hex format comparison failed: {check_resp2.get('message')}"
                )

            print("\n" + "=" * 60)
            print("✅ Robust Value Comparison Test Suite Completed!")
            print("=" * 60)

    except Exception as e:
        print(f"Connection error: {e}")


if __name__ == "__main__":
    asyncio.run(test_robust_value_comparison())
