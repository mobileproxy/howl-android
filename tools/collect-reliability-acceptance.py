"""Bounded, offline-by-default collector. It never performs acceptance scenarios itself.

ADB mode requires --adb --serial SERIAL --confirm-read-only. Only six fixed read-only
queries are allowed; serial, stdout/stderr, OS text and app private state are never emitted.
"""
import argparse
import json
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from pathlib import Path
import re
import subprocess
import sys
import threading


MAX_INPUT_BYTES = 65_536
MAX_COMMAND_BYTES = 32_768
MAX_OUTPUT_BYTES = 16_384
MAX_NUMBER = (1 << 63) - 1
COMMAND_TIMEOUT_SECONDS = 8
HEALTH_STATES = frozenset("Off Connecting Disconnecting Checking Available Recovering NoNetwork NoInternet Captive RequestsFailed Partial CheckUnavailable ProfileProblem Unverified".split())
HEALTH_CAUSES = frozenset("None NoPhysicalNetwork CaptiveNetwork PhysicalRequestsFailed SelectedRequestsFailed SystemNameResolution HttpsRequestFailed TlsHandshakeFailed ProbeUnavailable UnsupportedExit ProfileInvalid EvidenceExpired".split())
RECOVERY_CAUSES = frozenset("NONE UNKNOWN NO_PHYSICAL_NETWORK CAPTIVE_NETWORK PHYSICAL_REQUESTS_FAILED SELECTED_REQUESTS_FAILED SYSTEM_NAME_RESOLUTION HTTPS_REQUEST_FAILED TLS_HANDSHAKE_FAILED PROBE_UNAVAILABLE UNSUPPORTED_EXIT PROFILE_INVALID EVIDENCE_EXPIRED PROCESS_EXIT".split())
EXIT_REASONS = frozenset("UNKNOWN EXIT_SELF SIGNALED LOW_MEMORY CRASH NATIVE_CRASH ANR INITIALIZATION_FAILURE PERMISSION_CHANGE EXCESSIVE_RESOURCE USER_REQUESTED USER_STOPPED DEPENDENCY_DIED OTHER FREEZER PACKAGE_STATE_CHANGE PACKAGE_UPDATED".split())
START_KINDS = frozenset("USER STICKY GUARD_RESTART CONFIG_RELOAD BOOT PACKAGE_UPDATE".split())
STOP_KINDS = frozenset("ACTIVE USER REVOKE FAILURE RELOAD".split())
DEEP_IDLE = frozenset("ACTIVE INACTIVE IDLE_PENDING SENSING LOCATING IDLE IDLE_MAINTENANCE QUICK_DOZE_DELAY".split())
LIGHT_IDLE = frozenset("ACTIVE INACTIVE PRE_IDLE IDLE WAITING_FOR_NETWORK IDLE_MAINTENANCE OVERRIDE".split())
COMMANDS = (
    ("state", ("get-state",)),
    ("sdk", ("shell", "getprop", "ro.build.version.sdk")),
    ("uptime", ("shell", "cat", "/proc/uptime")),
    ("battery", ("shell", "dumpsys", "battery")),
    ("idle", ("shell", "dumpsys", "deviceidle")),
    ("package", ("shell", "dumpsys", "package", "how.vpn.howl")),
)
BUILTIN_FIXTURE = {
    "commands": {"state": "device\n", "sdk": "36\n", "uptime": "1234.56 991.10\n",
                 "battery": "level: 73\nscale: 100\nstatus: 3\ntemperature: 285\n",
                 "idle": "mState=ACTIVE mLightState=ACTIVE\n", "package": "versionCode=826 minSdk=24 targetSdk=36\n"},
    "report": {"schema": 1, "androidSdk": 36, "successfulProbes": 1, "probesMs": [20, None],
               "health": {"state": "Unverified", "cause": "None", "systemCause": "None", "scope": "None",
                          "checkedAgeMs": None, "temporaryFallback": False}, "recovery": []},
}


@dataclass(frozen=True)
class CommandResult:
    status: str
    output: bytes = b""


def run_bounded(command, timeout=8, max_bytes=32768):
    """Read at most max_bytes+1 bytes. Failed/late/oversized stdout is discarded.

    No shell, stdin, stderr capture, file output or retry. Only the local child is stopped
    on timeout/overflow; no command is issued to terminate or change the Android app.
    """
    if not 0 < timeout <= 30 or not 0 < max_bytes <= MAX_INPUT_BYTES:
        return CommandResult("ERROR")
    try:
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                   stderr=subprocess.DEVNULL, shell=False)
    except (OSError, ValueError):
        return CommandResult("UNAVAILABLE")
    captured = bytearray()
    overflow = threading.Event()
    read_error = threading.Event()

    def read():
        try:
            while len(captured) <= max_bytes:
                chunk = process.stdout.read(min(4096, max_bytes + 1 - len(captured)))
                if not chunk:
                    return
                captured.extend(chunk)
                if len(captured) > max_bytes:
                    overflow.set()
                    try:
                        process.terminate()
                    except OSError:
                        pass
                    return
        except (OSError, ValueError):
            read_error.set()

    reader = threading.Thread(target=read, daemon=True)
    reader.start()
    timed_out = False
    try:
        process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        timed_out = True
        try:
            process.kill()
        except OSError:
            pass
        try:
            process.wait(timeout=1)
        except subprocess.TimeoutExpired:
            pass
    reader.join(timeout=1)
    # A descendant retaining stdout cannot turn this bounded query into an indefinite wait.
    if reader.is_alive():
        return CommandResult("TIMEOUT")
    process.stdout.close()
    if timed_out:
        return CommandResult("TIMEOUT")
    if overflow.is_set():
        return CommandResult("OUTPUT_LIMIT")
    if process.returncode != 0 or read_error.is_set():
        return CommandResult("ERROR")
    return CommandResult("OK", bytes(captured))


def number(value, low=0, high=MAX_NUMBER):
    if type(value) is str and len(value) <= 19 and re.fullmatch(r"[0-9]+", value):
        value = int(value)
    if type(value) is int and low <= value <= high:
        return value
    return None


def fixed_code(value, allowed, default="UNKNOWN"):
    return value if type(value) is str and value in allowed else default


def copy_numbers(source, output, names, high=MAX_NUMBER):
    for name in names:
        value = number(source.get(name), high=high)
        if value is not None:
            output[name] = value


def sanitize_report(raw):
    """Additive ConnectionReport schema1 allowlist; no raw identity/strings survive."""
    if not isinstance(raw, dict) or type(raw.get("schema")) is not int or raw["schema"] != 1:
        return {}
    safe = {}
    sdk = number(raw.get("androidSdk"), low=1, high=99)
    if sdk is not None:
        safe["androidSdk"] = sdk
    copy_numbers(raw, safe, ("successfulProbes",), high=16)
    copy_numbers(raw, safe, ("downloadBytes",), high=1_048_576)
    copy_numbers(raw, safe, ("downloadMs",), high=604_800_000)
    if isinstance(raw.get("probesMs"), list):
        safe["probesMs"] = [number(item, high=120_000) for item in raw["probesMs"][:16]]
    if isinstance(raw.get("events"), dict):
        safe["events"] = {}
        copy_numbers(raw["events"], safe["events"], ("sampledLines", "networkChanges", "watchdogEvents", "coreEvents"), high=2000)
    if isinstance(raw.get("health"), dict):
        health = raw["health"]
        safe["health"] = {"state": fixed_code(health.get("state"), HEALTH_STATES),
                          "cause": fixed_code(health.get("cause"), HEALTH_CAUSES),
                          "systemCause": fixed_code(health.get("systemCause"), HEALTH_CAUSES),
                          "scope": fixed_code(health.get("scope"), {"None", "SelectedExitResponse"})}
        copy_numbers(health, safe["health"], ("checkedAgeMs",), high=120_000)
        if health.get("checkedAgeMs", -1) is None:
            safe["health"]["checkedAgeMs"] = None
        if type(health.get("temporaryFallback")) is bool:
            safe["health"]["temporaryFallback"] = health["temporaryFallback"]
    if isinstance(raw.get("serviceRecovery"), dict):
        source = raw["serviceRecovery"]
        lifecycle = {"startKind": fixed_code(source.get("startKind"), START_KINDS),
                     "plannedStop": fixed_code(source.get("plannedStop"), STOP_KINDS),
                     "previousExitKind": fixed_code(source.get("previousExitKind"), {"UNKNOWN", "UNSUPPORTED", "PLANNED", "MATCHED"}),
                     "previousExitReason": fixed_code(source.get("previousExitReason"), EXIT_REASONS)}
        copy_numbers(source, lifecycle, ("serviceAttempts", "serviceSuccesses", "serviceCancelled", "networkAttempts", "networkSuccesses", "networkCancelled"))
        copy_numbers(source, lifecycle, ("serviceLastDurationMs", "networkLastDurationMs"), high=604_800_000)
        safe["serviceRecovery"] = lifecycle
    if isinstance(raw.get("recovery"), list):
        summaries = []
        for value in raw["recovery"][:8]:
            if not isinstance(value, dict):
                continue
            summary = {"cause": fixed_code(value.get("cause"), RECOVERY_CAUSES),
                       "result": fixed_code(value.get("result"), {"FAILED", "UNAVAILABLE", "RECOVERED", "CANCELLED"})}
            copy_numbers(value, summary, ("firstElapsedMs", "lastElapsedMs", "count", "attempts", "droppedSummaries"))
            summaries.append(summary)
        safe["recovery"] = summaries
    return safe


def parse_battery(raw):
    values = {}
    for name in ("level", "scale", "status", "temperature"):
        found = re.search(r"(?m)^\s*" + name + r":\s*(-?[0-9]{1,6})\s*$", raw)
        if found:
            values[name] = int(found[1])
    output = {}
    if 0 <= values.get("level", -1) <= 100 and 1 <= values.get("scale", 0) <= 100 and values["level"] <= values["scale"]:
        output["levelPercent"] = values["level"] * 100 // values["scale"]
    if 1 <= values.get("status", -1) <= 5:
        output["statusCode"] = values["status"]
    if -200 <= values.get("temperature", -1000) <= 1000:
        output["temperatureTenthsC"] = values["temperature"]
    return output


def parse_idle(raw):
    output = {}
    for name, field, allowed in (("deep", "mState", DEEP_IDLE), ("light", "mLightState", LIGHT_IDLE)):
        found = re.search(r"\b" + field + r"=([A-Z_]+)(?=\s|$)", raw)
        if found and found[1] in allowed:
            output[name] = found[1]
    return output


def parse_device(outputs):
    device = {"connection": "DEVICE" if outputs.get("state", "").strip() == "device" else "UNAVAILABLE"}
    sdk = number(outputs.get("sdk", "").strip(), low=1, high=99)
    if sdk is not None:
        device["androidSdk"] = sdk
    uptime = re.fullmatch(r"([0-9]{1,12}(?:\.[0-9]{1,3})?) [0-9]{1,12}(?:\.[0-9]{1,3})?\s*", outputs.get("uptime", ""))
    if uptime:
        try:
            milliseconds = int(Decimal(uptime[1]) * 1000)
            if 0 <= milliseconds <= MAX_NUMBER:
                device["uptimeMs"] = milliseconds
        except InvalidOperation:
            pass
    device["battery"] = parse_battery(outputs.get("battery", ""))
    device["idle"] = parse_idle(outputs.get("idle", ""))
    version = re.search(r"\bversionCode=([0-9]{1,10})(?=\s|$)", outputs.get("package", ""))
    if version:
        value = number(version[1], low=1, high=2_100_000_000)
        if value is not None:
            device["appVersionCode"] = value
    return device


class InputFailure(Exception):
    def __init__(self, code):
        self.code = code


class QuietParser(argparse.ArgumentParser):
    def error(self, _message):
        raise InputFailure("INVALID_ARGUMENTS")


def read_json(path):
    try:
        with Path(path).open("rb") as stream:
            content = stream.read(MAX_INPUT_BYTES + 1)
    except OSError:
        raise InputFailure("INPUT_UNAVAILABLE") from None
    if len(content) > MAX_INPUT_BYTES:
        raise InputFailure("INVALID_INPUT")
    try:
        value = json.loads(content.decode("utf-8"))
    except (ValueError, UnicodeError, RecursionError):
        raise InputFailure("INVALID_INPUT") from None
    if not isinstance(value, dict):
        raise InputFailure("INVALID_INPUT")
    return value


def fixture_outputs(fixture):
    commands = fixture.get("commands")
    if not isinstance(commands, dict):
        raise InputFailure("INVALID_INPUT")
    result = {}
    for name, _command in COMMANDS:
        value = commands.get(name, "")
        if not isinstance(value, str) or len(value.encode("utf-8")) > MAX_COMMAND_BYTES:
            raise InputFailure("INVALID_INPUT")
        result[name] = value
    return result


def collect_adb(serial, runner):
    outputs = {}
    statuses = {}
    for name, tail in COMMANDS:
        result = runner(["adb", "-s", serial, *tail], timeout=COMMAND_TIMEOUT_SECONDS, max_bytes=MAX_COMMAND_BYTES)
        status = fixed_code(result.status, {"OK", "ERROR", "UNAVAILABLE", "TIMEOUT", "OUTPUT_LIMIT"}, "ERROR")
        statuses[name] = status
        if status == "OK" and isinstance(result.output, bytes) and len(result.output) <= MAX_COMMAND_BYTES:
            try:
                outputs[name] = result.output.decode("utf-8")
            except UnicodeError:
                statuses[name] = "INVALID_INPUT"
        elif status == "OK":
            statuses[name] = "OUTPUT_LIMIT"
        if name == "state" and outputs.get("state", "").strip() != "device":
            break
    return outputs, statuses


def emit(report):
    encoded = json.dumps(report, ensure_ascii=True, allow_nan=False, sort_keys=True, separators=(",", ":"))
    if len(encoded.encode("ascii")) > MAX_OUTPUT_BYTES:
        encoded = '{"schema":1,"collectionStatus":"ERROR","errorCode":"OUTPUT_LIMIT","physicalAcceptance":"NOT_RUN"}'
    print(encoded)


def main(argv=None, runner=None):
    parser = QuietParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--adb", action="store_true", help="Explicitly enable six read-only ADB queries")
    parser.add_argument("--serial", help="Exactly one selected device; never emitted")
    parser.add_argument("--confirm-read-only", action="store_true", help="Opt into the documented ADB reads")
    parser.add_argument("--fixture", help="Bounded offline fixture JSON (default: built-in synthetic fixture)")
    parser.add_argument("--report", help="Optional existing ConnectionReport schema1 JSON to sanitize")
    try:
        args = parser.parse_args(argv)
        mode = "ADB_READ_ONLY" if args.adb else "FIXTURE"
        if args.adb:
            if not args.serial or not args.confirm_read_only:
                raise InputFailure("AUTH_REQUIRED")
            if args.fixture or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}", args.serial):
                raise InputFailure("INVALID_ARGUMENTS")
            outputs, statuses = collect_adb(args.serial, runner or run_bounded)
            report_input = None
            source = "UNKNOWN"
        else:
            if args.serial or args.confirm_read_only:
                raise InputFailure("AUTH_REQUIRED")
            fixture = read_json(args.fixture) if args.fixture else BUILTIN_FIXTURE
            outputs = fixture_outputs(fixture)
            statuses = {name: "FIXTURE" for name, _command in COMMANDS}
            report_input = fixture.get("report")
            source = "FIXTURE"
        if args.report:
            report_input = read_json(args.report)
            if type(report_input.get("schema")) is not int or report_input["schema"] != 1:
                raise InputFailure("INVALID_INPUT")
            source = "SANITIZED_INPUT"
        evidence = sanitize_report(report_input)
        device = parse_device(outputs)
        complete = device["connection"] == "DEVICE" and all(value in {"OK", "FIXTURE"} for value in statuses.values())
        report = {"schema": 1, "mode": mode, "physicalAcceptance": "NOT_RUN",
                  "collectionStatus": "OK" if complete else "PARTIAL", "device": device,
                  "commandStatus": statuses, "application": {"source": source, "evidence": evidence},
                  "scenarioStatus": {f"H{index:02}": "NOT_RUN" for index in range(1, 25)}}
        emit(report)
        return 0 if complete else 1
    except InputFailure as failure:
        emit({"schema": 1, "collectionStatus": "ERROR", "errorCode": failure.code, "physicalAcceptance": "NOT_RUN"})
        return 2 if failure.code == "INVALID_ARGUMENTS" else 1
    except KeyboardInterrupt:
        emit({"schema": 1, "collectionStatus": "ERROR", "errorCode": "CANCELLED", "physicalAcceptance": "NOT_RUN"})
        return 130
    except Exception:
        # Boundary failures are a fixed code. Never echo an exception, command or file path.
        emit({"schema": 1, "collectionStatus": "ERROR", "errorCode": "COLLECTION_FAILED", "physicalAcceptance": "NOT_RUN"})
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
