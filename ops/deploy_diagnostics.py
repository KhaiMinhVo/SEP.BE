"""Read-only diagnostics; never print raw logs, environment or error messages."""
import collections
import json
import re
import subprocess
import sys


def fingerprints(raw):
    events = collections.Counter()
    categories = {
        "authentication-failed": r"authentication failed|authenticationfailedexception",
        "connection-refused": r"connection refused|connectexception",
        "timeout": r"timed?\s*out|timeoutexception",
        "dns-resolution-failed": r"unknownhostexception|name or service not known",
        "migration-validation-failed": r"checksum mismatch|flywayvalidateexception",
        "disk-full": r"no space left on device",
        "out-of-memory": r"outofmemoryerror",
        "permission-denied": r"permission denied",
    }
    for line in raw.splitlines():
        if re.search(r"healthindicator|caused by:|exception|\berror\b|\bwarn\b", line, re.I):
            for name in set(re.findall(r"\b(?:[a-zA-Z_][\w]*\.)*[A-Z][A-Za-z0-9]*(?:HealthIndicator|Exception|Error)\b", line)):
                events[("java-class", name)] += 1
            for label, pattern in categories.items():
                if re.search(pattern, line, re.I):
                    events[("failure-category", label)] += 1
    return [{"kind": kind, "name": name, "occurrences": count}
            for (kind, name), count in events.most_common(40)]


def command(args):
    try:
        result = subprocess.run(args, capture_output=True, text=True, timeout=25, check=False)
        return result.returncode, result.stdout + result.stderr
    except (OSError, subprocess.TimeoutExpired):
        return -1, ""


def main(container):
    print("=== BACKEND DIAGNOSTICS (read-only; raw logs and secrets omitted) ===")
    code, raw = command(["docker", "inspect", container])
    if code:
        print("Container inspection unavailable.")
        return
    try:
        data = json.loads(raw)[0]
        state = data.get("State", {})
        health = state.get("Health", {})
        summary = {
            "containerStatus": state.get("Status") if state.get("Status") in {"created", "running", "paused", "restarting", "removing", "exited", "dead"} else "unknown",
            "health": health.get("Status") if health.get("Status") in {"starting", "healthy", "unhealthy"} else "unknown",
            "oomKilled": state.get("OOMKilled") is True,
            "exitCode": state.get("ExitCode") if type(state.get("ExitCode")) is int else None,
            "restartCount": data.get("RestartCount") if type(data.get("RestartCount")) is int else None,
            "recentHealthExitCodes": [item.get("ExitCode") for item in health.get("Log", [])[-5:] if type(item.get("ExitCode")) is int],
        }
        print(json.dumps(summary))
    except (ValueError, KeyError, TypeError, IndexError):
        print("Container state could not be decoded; raw response omitted.")
    code, logs = command(["docker", "logs", "--since", "20m", "--tail", "600", container])
    if code:
        print("Container logs unavailable; raw error omitted.")
        return
    found = fingerprints(logs)
    print(json.dumps({"logFingerprints": found}))
    if not found:
        print("No recognized error fingerprints. VPS owner must inspect private logs.")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "influencermatch_api")
