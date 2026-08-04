"""Unit tests for mesh_lab.py orchestration that do not require hardware.

FakeDevice stands in for the ADB-backed Device: it answers test-hook commands
with canned JSON so the three-phone multi-hop scenario logic can be validated
without any phone plugged in. Physical behavior is covered by the ADB mesh lab
runbook, not by these tests.
"""

import time
import unittest
import threading

from tools.release_gate import mesh_lab


class FakeDevice:
    """In-memory Device stand-in: records commands, returns canned results."""

    def __init__(
        self,
        alias: str,
        peer_id: str,
        peers: list[dict],
        bus: dict | None = None,
        route_metrics: dict | None = None,
    ):
        self.alias = alias
        self.peer_id = peer_id
        self.peers = peers
        self.commands: list[str] = []
        self._lock = threading.Lock()
        # Shared across devices in a scenario, standing in for the over-the-air path.
        self.bus = bus if bus is not None else {}
        self.route_metrics = route_metrics or {
            "status": "ok",
            "relay_success": {},
            "relay_failure": {},
            "routed_drops": 0,
            "fallback_floods": 0,
            "path_changes": 0,
            "routes_planned": 0,
            "hop_profile": {},
        }

    def cmd(self, cmd: str, timeout_ms: int = 60_000, **extras):
        with self._lock:
            self.commands.append(cmd)
        if cmd == "whoami":
            return {"status": "ok", "peer_id": self.peer_id}
        if cmd == "peers":
            return {"status": "ok", "peers": list(self.peers)}
        if cmd == "announce":
            return {"status": "ok"}
        if cmd == "connect":
            return {"status": "ok", "direct": True}
        if cmd == "handshake":
            return {"status": "ok"}
        if cmd == "dm_send":
            with self._lock:
                self.bus["dm_content"] = extras.get("content", "")
            return {"status": "ok"}
        if cmd == "dm_recv":
            # Like the real device, poll until the expected content arrives.
            deadline = time.monotonic() + max(timeout_ms or 0, 5_000) / 1000
            contains = extras.get("contains")
            while True:
                with self._lock:
                    content = self.bus.get("dm_content", "")
                if contains is None or contains in content:
                    return {"status": "ok", "content": content, "from": "id-a"}
                if time.monotonic() >= deadline:
                    return {"status": "error", "content": content}
                time.sleep(0.05)
        if cmd == "route_metrics":
            with self._lock:
                return dict(self.route_metrics)
        return {"status": "ok"}

    def cmd_ok(self, cmd: str, timeout_ms: int = 60_000, **extras):
        result = self.cmd(cmd, timeout_ms=timeout_ms, **extras)
        if result.get("status") != "ok":
            raise mesh_lab.MeshLabError(f"[{self.alias}] '{cmd}' failed: {result}")
        return result

    def logcat_dump(self, lines: int = 200) -> str:
        return ""


class ScenarioMultiHopTest(unittest.TestCase):
    def test_multi_hop_dm_reaches_c_via_b(self):
        id_a, id_b, id_c = "id-a", "id-b", "id-c"
        bus: dict = {}
        a = FakeDevice(
            "alpha", id_a,
            peers=[{"id": id_b, "direct": True}, {"id": id_c, "direct": False}],
            bus=bus,
        )
        b = FakeDevice(
            "beta", id_b,
            peers=[{"id": id_a, "direct": True}, {"id": id_c, "direct": True}],
            bus=bus,
            route_metrics={
                "status": "ok",
                "relay_success": {"BLE": 2},
                "relay_failure": {},
                "routed_drops": 0,
                "fallback_floods": 0,
                "path_changes": 1,
                "routes_planned": 1,
                "hop_profile": {"2": 1},
            },
        )
        c = FakeDevice(
            "gamma", id_c,
            peers=[{"id": id_b, "direct": True}, {"id": id_a, "direct": False}],
            bus=bus,
        )

        result = mesh_lab.scenario_multi_hop(a, b, c)

        self.assertEqual(result["topology"], {"a": id_a, "hub_b": id_b, "c": id_c})
        recv = result["a_to_c"]["recv"]
        self.assertIn("mh-", recv["content"])
        self.assertEqual(recv["from"], id_a)
        self.assertIn("dm_send", a.commands)
        self.assertIn("dm_recv", c.commands)
        # The hub must have been driven to connect to both neighbors.
        self.assertGreaterEqual(b.commands.count("connect"), 2)
        # The hub's route metrics must show it relayed (unicast or flood).
        self.assertIn("route_metrics", result)
        self.assertGreater(
            sum(result["route_metrics"]["b"].get("relay_success", {}).values()),
            0,
        )

    def test_multi_hop_fails_when_a_has_direct_link_to_c(self):
        id_a, id_b, id_c = "id-a", "id-b", "id-c"
        a = FakeDevice(
            "alpha", id_a,
            peers=[{"id": id_b, "direct": True}, {"id": id_c, "direct": True}],
        )
        b = FakeDevice("beta", id_b, peers=[{"id": id_a, "direct": True}, {"id": id_c, "direct": True}])
        c = FakeDevice("gamma", id_c, peers=[{"id": id_b, "direct": True}])

        with self.assertRaises(mesh_lab.MeshLabError):
            mesh_lab.scenario_multi_hop(a, b, c)

    def test_run_scenario_requires_serial_c_for_multi_hop(self):
        a = FakeDevice("alpha", "id-a", peers=[])
        b = FakeDevice("beta", "id-b", peers=[])
        evidence = mesh_lab.run_scenario("multi_hop", a, b, out=None, c=None)
        self.assertEqual(evidence["status"], "fail")
        self.assertIn("--serial-c", evidence["error"])

    def test_multi_hop_fails_when_hub_shows_no_relay_activity(self):
        id_a, id_b, id_c = "id-a", "id-b", "id-c"
        bus: dict = {}
        a = FakeDevice(
            "alpha", id_a,
            peers=[{"id": id_b, "direct": True}, {"id": id_c, "direct": False}],
            bus=bus,
        )
        # Hub B's counters stay empty: the DM may have reached C some other way.
        b = FakeDevice(
            "beta", id_b,
            peers=[{"id": id_a, "direct": True}, {"id": id_c, "direct": True}],
            bus=bus,
        )
        c = FakeDevice(
            "gamma", id_c,
            peers=[{"id": id_b, "direct": True}, {"id": id_a, "direct": False}],
            bus=bus,
        )

        with self.assertRaises(mesh_lab.MeshLabError) as caught:
            mesh_lab.scenario_multi_hop(a, b, c)
        self.assertIn("no relay activity", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
