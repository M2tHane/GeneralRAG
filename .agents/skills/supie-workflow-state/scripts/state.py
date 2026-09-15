#!/usr/bin/env python3
"""Manage `.supie/state/current.yaml` with the Python stdlib only.

The file contains JSON text. JSON is valid YAML 1.2, so ZCode and humans can read it
without adding PyYAML as a workflow dependency. Writes are atomic.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
from pathlib import Path

STATE = Path(".supie/state/current.yaml")


def now() -> str:
    return dt.datetime.now(dt.timezone.utc).astimezone().isoformat(timespec="seconds")


def load() -> dict:
    if not STATE.exists():
        return {}
    try:
        value = json.loads(STATE.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise SystemExit(f"invalid workflow state: {STATE}: {exc}") from exc
    if not isinstance(value, dict):
        raise SystemExit(f"invalid workflow state: {STATE} must contain an object")
    return value


def write(data: dict) -> None:
    STATE.parent.mkdir(parents=True, exist_ok=True)
    data["updated_at"] = now()
    tmp = STATE.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(STATE)


def require_active(data: dict) -> None:
    if not data or not data.get("flow"):
        raise SystemExit("no active Supie workflow; run `state.py start ...` first")


def main() -> None:
    parser = argparse.ArgumentParser(description="Supie runtime workflow state helper")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("start")
    p.add_argument("--flow", required=True)
    p.add_argument("--mode", default="standard", choices=["fast", "standard", "strict"])
    p.add_argument("--goal", required=True)
    p.add_argument("--baseline", default="n/a")

    p = sub.add_parser("stage")
    p.add_argument("number", type=int)
    p.add_argument("status", choices=["pending", "in_progress", "completed", "skipped", "blocked"])
    p.add_argument("--approved", choices=["true", "false"])
    p.add_argument("--artifact")
    p.add_argument("--note")

    p = sub.add_parser("task")
    p.add_argument("task_id")
    p.add_argument("status", choices=["pending", "in_progress", "completed", "blocked", "skipped"])
    p.add_argument("--note")

    p = sub.add_parser("verify")
    p.add_argument("label")
    p.add_argument("result", choices=["pass", "fail", "partial", "blocked"])
    p.add_argument("--command")
    p.add_argument("--note")

    p = sub.add_parser("finish")
    p.add_argument("--status", default="completed", choices=["completed", "blocked", "cancelled"])
    p.add_argument("--note")

    sub.add_parser("show")
    sub.add_parser("clear")

    args = parser.parse_args()

    if args.cmd == "start":
        data = {
            "schema": 1,
            "flow": args.flow,
            "mode": args.mode,
            "status": "running",
            "goal": args.goal,
            "baseline": args.baseline,
            "current_stage": 1,
            "stages": {},
            "tasks": {},
            "verification": [],
            "started_at": now(),
        }
        write(data)
        return

    if args.cmd == "show":
        print(STATE.read_text(encoding="utf-8") if STATE.exists() else "(no active state)", end="")
        return

    if args.cmd == "clear":
        if STATE.exists():
            STATE.unlink()
        return

    data = load()
    require_active(data)

    if args.cmd == "stage":
        data["current_stage"] = args.number
        entry = {"status": args.status}
        if args.approved is not None:
            entry["approved"] = args.approved == "true"
        if args.artifact:
            entry["artifact"] = args.artifact
        if args.note:
            entry["note"] = args.note
        data.setdefault("stages", {})[str(args.number)] = entry
    elif args.cmd == "task":
        entry = {"status": args.status}
        if args.note:
            entry["note"] = args.note
        data.setdefault("tasks", {})[args.task_id] = entry
    elif args.cmd == "verify":
        entry = {"label": args.label, "result": args.result, "at": now()}
        if args.command:
            entry["command"] = args.command
        if args.note:
            entry["note"] = args.note
        data.setdefault("verification", []).append(entry)
    elif args.cmd == "finish":
        data["status"] = args.status
        data["finished_at"] = now()
        if args.note:
            data["finish_note"] = args.note

    write(data)


if __name__ == "__main__":
    main()
