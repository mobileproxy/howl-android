import contextlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import time
import unittest
import uuid


SPEC = importlib.util.spec_from_file_location(
    "reliability_collector", Path(__file__).resolve().parents[1] / "collect-reliability-acceptance.py"
)
collector = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = collector
SPEC.loader.exec_module(collector)


@contextlib.contextmanager
def fixture_path():
    path = Path(__file__).resolve().parent / (".kit-fixture-" + uuid.uuid4().hex + ".json")
    try:
        yield path
    finally:
        path.unlink(missing_ok=True)


class CollectorTests(unittest.TestCase):
    def invoke(self, arguments=(), runner=None):
        output = io.StringIO()
        errors = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            result = collector.main(list(arguments), runner=runner)
        self.assertEqual("", errors.getvalue())
        value = output.getvalue()
        self.assertTrue(value.startswith("{"), value[:50])
        return result, json.loads(value), value

    @staticmethod
    def no_adb(*_args, **_kwargs):
        raise AssertionError("Offline/unauthorized mode must not start ADB")

    def fake_adb(self, calls, state=b"device\n", failing=None):
        responses = {
            ("get-state",): state,
            ("shell", "getprop", "ro.build.version.sdk"): b"36\n",
            ("shell", "cat", "/proc/uptime"): b"1234.56 991.10\n",
            ("shell", "dumpsys", "battery"): b"Battery Service state:\n  level: 73\n  scale: 100\n  status: 3\n  temperature: 285\n  secret: token-password\n",
            ("shell", "dumpsys", "deviceidle"): b"mState=IDLE mLightState=OVERRIDE\nprivate.server.invalid\n",
            ("shell", "dumpsys", "package", "how.vpn.howl"): b"Package [how.vpn.howl]:\n versionCode=826 minSdk=24 targetSdk=36\n secret=token-password\n",
        }

        def run(command, **_kwargs):
            calls.append(command)
            self.assertEqual(["adb", "-s", "private-device-42"], command[:3])
            tail = tuple(command[3:])
            self.assertIn(tail, responses)
            if tail == failing:
                return collector.CommandResult("ERROR", b"private-key-password")
            return collector.CommandResult("OK", responses[tail])

        return run

    def test_default_is_fixture_and_never_calls_adb(self):
        result, report, _ = self.invoke(runner=self.no_adb)
        self.assertEqual(0, result)
        self.assertEqual("FIXTURE", report["mode"])
        self.assertEqual("NOT_RUN", report["physicalAcceptance"])
        self.assertEqual({f"H{number:02}": "NOT_RUN" for number in range(1, 25)}, report["scenarioStatus"])
        self.assertEqual("FIXTURE", report["application"]["source"])

    def test_missing_explicit_opt_in_never_runs_adb(self):
        for arguments in (("--adb",), ("--adb", "--serial", "secret-device"),
                          ("--adb", "--confirm-read-only"), ("--serial", "secret-device")):
            result, report, raw = self.invoke(arguments, self.no_adb)
            self.assertNotEqual(0, result)
            self.assertIn("errorCode", report)
            self.assertEqual("AUTH_REQUIRED", report["errorCode"])
            self.assertNotIn("secret-device", raw)

    def test_invalid_serial_or_conflicting_fixture_never_runs_adb(self):
        for tail in (("--serial", "device;private-password"),
                     ("--serial", "-device"), ("--serial", "x" * 129),
                     ("--serial", "private-device-42", "--fixture", "secret-path.json")):
            result, report, raw = self.invoke(("--adb", "--confirm-read-only", *tail), self.no_adb)
            self.assertNotEqual(0, result)
            self.assertEqual("ERROR", report["collectionStatus"])
            self.assertNotIn("private-password", raw)
            self.assertNotIn("secret-path", raw)

    def test_explicit_adb_executes_only_fixed_read_only_commands_for_one_serial(self):
        calls = []
        result, report, raw = self.invoke(
            ("--adb", "--serial", "private-device-42", "--confirm-read-only"), self.fake_adb(calls)
        )
        self.assertEqual(0, result)
        self.assertEqual([
            ["adb", "-s", "private-device-42", "get-state"],
            ["adb", "-s", "private-device-42", "shell", "getprop", "ro.build.version.sdk"],
            ["adb", "-s", "private-device-42", "shell", "cat", "/proc/uptime"],
            ["adb", "-s", "private-device-42", "shell", "dumpsys", "battery"],
            ["adb", "-s", "private-device-42", "shell", "dumpsys", "deviceidle"],
            ["adb", "-s", "private-device-42", "shell", "dumpsys", "package", "how.vpn.howl"],
        ], calls)
        self.assertEqual("ADB_READ_ONLY", report["mode"])
        self.assertEqual("UNKNOWN", report["application"]["source"])
        self.assertEqual(36, report["device"]["androidSdk"])
        self.assertEqual(1234560, report["device"]["uptimeMs"])
        self.assertEqual(826, report["device"]["appVersionCode"])
        self.assertEqual({"levelPercent": 73, "statusCode": 3, "temperatureTenthsC": 285}, report["device"]["battery"])
        self.assertEqual({"deep": "IDLE", "light": "OVERRIDE"}, report["device"]["idle"])
        for secret in ("private-device", "private.server", "token-password", "how.vpn.howl"):
            self.assertNotIn(secret, raw)
        self.assertEqual("NOT_RUN", report["physicalAcceptance"])

    def test_unavailable_device_skips_all_other_commands(self):
        calls = []
        result, report, raw = self.invoke(
            ("--adb", "--serial", "private-device-42", "--confirm-read-only"),
            self.fake_adb(calls, state=b"unauthorized: private-device-secret\n"),
        )
        self.assertEqual(1, len(calls))
        self.assertEqual(1, result)
        self.assertEqual("UNAVAILABLE", report["device"]["connection"])
        self.assertNotIn("secret", raw)

    def test_command_failure_does_not_echo_stdout_or_error(self):
        calls = []
        result, report, raw = self.invoke(
            ("--adb", "--serial", "private-device-42", "--confirm-read-only"),
            self.fake_adb(calls, failing=("shell", "dumpsys", "battery")),
        )
        self.assertEqual(1, result)
        self.assertEqual("PARTIAL", report["collectionStatus"])
        self.assertEqual({}, report["device"]["battery"])
        self.assertNotIn("private-key-password", raw)

    def test_report_allowlist_keeps_codes_counters_durations_only(self):
        raw_report = {
            "schema": 1, "androidSdk": 36, "appVersion": "private-server-password",
            "before": {"alias": "private-node", "protocol": "secret"}, "probesMs": [10, None, 30],
            "successfulProbes": 2, "downloadBytes": 1024, "downloadMs": 25,
            "events": {"sampledLines": 100, "networkChanges": 2, "watchdogEvents": 3, "coreEvents": 4, "rawLog": "token"},
            "health": {"state": "Available", "cause": "None", "systemCause": "None", "scope": "SelectedExitResponse",
                       "checkedAgeMs": 20, "temporaryFallback": True, "selected": "private-host"},
            "serviceRecovery": {"startKind": "STICKY", "plannedStop": "ACTIVE", "previousExitKind": "MATCHED",
                                "previousExitReason": "LOW_MEMORY", "serviceAttempts": 1, "serviceSuccesses": 1,
                                "networkAttempts": 2, "networkSuccesses": 1, "networkLastDurationMs": 200,
                                "pid": 999, "processName": "private-process"},
            "recovery": [{"cause": "SELECTED_REQUESTS_FAILED", "result": "RECOVERED", "firstElapsedMs": "100",
                          "lastElapsedMs": "200", "count": "3", "attempts": "2", "droppedSummaries": "0", "error": "password"}],
            "configuration": {"token": "secret"},
        }
        safe = collector.sanitize_report(raw_report)
        self.assertIn("health", safe)
        self.assertIn("serviceRecovery", safe)
        self.assertIn("recovery", safe)
        self.assertEqual("Available", safe["health"]["state"])
        self.assertEqual(True, safe["health"]["temporaryFallback"])
        self.assertEqual(200, safe["serviceRecovery"]["networkLastDurationMs"])
        self.assertEqual(3, safe["recovery"][0]["count"])
        self.assertEqual([10, None, 30], safe["probesMs"])
        self.assertEqual({"sampledLines": 100, "networkChanges": 2, "watchdogEvents": 3, "coreEvents": 4}, safe["events"])
        encoded = json.dumps(safe)
        for secret in ("private", "password", "token", "configuration", "processName", "pid"):
            self.assertNotIn(secret, encoded)

    def test_report_rejects_unknown_schema_codes_numbers_and_oversized_lists(self):
        self.assertEqual({}, collector.sanitize_report({"schema": 99, "androidSdk": 36}))
        safe = collector.sanitize_report({"schema": 1, "androidSdk": True, "downloadBytes": -1,
            "probesMs": list(range(100)), "health": {"state": "https://private/password", "checkedAgeMs": 999999,
                "cause": "None", "temporaryFallback": "token"},
            "recovery": [{"cause": "https://private/password", "result": "FAILED", "count": 1}] * 100})
        self.assertNotIn("androidSdk", safe)
        self.assertNotIn("downloadBytes", safe)
        self.assertIn("probesMs", safe)
        self.assertIn("recovery", safe)
        self.assertIn("health", safe)
        self.assertLessEqual(len(safe["probesMs"]), 16)
        self.assertLessEqual(len(safe["recovery"]), 8)
        self.assertEqual("UNKNOWN", safe["health"]["state"])
        self.assertNotIn("checkedAgeMs", safe["health"])
        self.assertNotIn("temporaryFallback", safe["health"])
        self.assertNotIn("private", json.dumps(safe))

    def test_oversized_or_malformed_fixture_never_echoes_input(self):
        with fixture_path() as path:
            for contents in (b"password" * 10000, b'{"commands": "private-password"', b"[1,2,3]", b"\xff"):
                path.write_bytes(contents)
                result, report, raw = self.invoke(("--fixture", str(path)), self.no_adb)
                self.assertNotEqual(0, result)
                self.assertIn("errorCode", report)
                self.assertEqual("INVALID_INPUT", report["errorCode"])
                self.assertNotIn("password", raw)
                self.assertLess(len(raw.encode()), 16384)

    def test_explicit_report_file_is_sanitized_without_adb(self):
        with fixture_path() as path:
            path.write_text(json.dumps({"schema": 1, "androidSdk": 35, "secret": "password"}), encoding="utf-8")
            result, report, raw = self.invoke(("--report", str(path)), self.no_adb)
            self.assertEqual(0, result)
            self.assertEqual("SANITIZED_INPUT", report["application"]["source"])
            self.assertEqual(35, report["application"]["evidence"]["androidSdk"])
            self.assertNotIn("private-file", raw)
            self.assertNotIn("password", raw)

    def test_missing_input_path_is_fixed_error_without_private_path(self):
        result, report, raw = self.invoke(("--fixture", "/private-password/no-such-input.json"), self.no_adb)
        self.assertNotEqual(0, result)
        self.assertIn("errorCode", report)
        self.assertEqual("INPUT_UNAVAILABLE", report["errorCode"])
        self.assertNotIn("private-password", raw)

    def test_unsupported_report_schema_is_unknown_input_error_without_echo(self):
        with fixture_path() as path:
            path.write_text(json.dumps({"schema": 999, "health": "private-password"}), encoding="utf-8")
            result, report, raw = self.invoke(("--report", str(path)), self.no_adb)
            self.assertNotEqual(0, result)
            self.assertEqual("INVALID_INPUT", report["errorCode"])
            self.assertNotIn("private-password", raw)

    def test_successful_transport_with_oversized_stdout_is_not_parsed(self):
        calls = []
        fake = self.fake_adb(calls)

        def run(command, **kwargs):
            if command[-1] == "battery":
                return collector.CommandResult("OK", b"private-password" * 10_000)
            return fake(command, **kwargs)

        result, report, raw = self.invoke(
            ("--adb", "--serial", "private-device-42", "--confirm-read-only"), run)
        self.assertEqual(1, result)
        self.assertEqual("OUTPUT_LIMIT", report["commandStatus"]["battery"])
        self.assertEqual({}, report["device"]["battery"])
        self.assertNotIn("private-password", raw)

    def test_unrecognized_cli_argument_is_fixed_error_without_echo(self):
        result, report, raw = self.invoke(("--private-token-password",), self.no_adb)
        self.assertEqual(2, result)
        self.assertEqual("INVALID_ARGUMENTS", report["errorCode"])
        self.assertNotIn("private-token", raw)

    def test_battery_and_idle_parsers_reject_embedded_strings_and_out_of_range_numbers(self):
        self.assertEqual({}, collector.parse_battery("level: 9999\nscale: 0\nstatus: 42\ntemperature: 99999\n"))
        self.assertEqual({}, collector.parse_idle("mState=private-password mLightState=private-host"))
        self.assertEqual({}, collector.parse_battery("level: 70-private-password\nscale: 100\n"))

    def test_run_command_bounds_stdout_and_discards_output_on_limit(self):
        result = collector.run_bounded([sys.executable, "-c", "import sys;sys.stdout.write('private-password'*10000)"],
                                       timeout=2, max_bytes=2048)
        self.assertEqual("OUTPUT_LIMIT", result.status)
        self.assertEqual(b"", result.output)

    def test_run_command_has_timeout_and_never_returns_partial_private_output(self):
        started = time.monotonic()
        result = collector.run_bounded([sys.executable, "-c", "import time;print('private-password',flush=True);time.sleep(5)"],
                                       timeout=0.05, max_bytes=2048)
        self.assertEqual("TIMEOUT", result.status)
        self.assertEqual(b"", result.output)
        self.assertLess(time.monotonic() - started, 3)

    def test_run_command_drops_stderr_and_failed_stdout(self):
        result = collector.run_bounded([sys.executable, "-c", "import sys;print('private-password');sys.stderr.write('private-token');sys.exit(7)"],
                                       timeout=2, max_bytes=2048)
        self.assertEqual("ERROR", result.status)
        self.assertEqual(b"", result.output)

    def test_successful_local_child_returns_bounded_stdout_and_discards_stderr(self):
        result = collector.run_bounded([sys.executable, "-c", "import sys;print('36');sys.stderr.write('private-token')"],
                                       timeout=2, max_bytes=2048)
        self.assertEqual("OK", result.status)
        self.assertEqual(b"36\r\n" if sys.platform == "win32" else b"36\n", result.output)


if __name__ == "__main__":
    unittest.main()
