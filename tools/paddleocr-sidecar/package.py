#!/usr/bin/env python3
"""Build a platform-specific PaddleOCR sidecar ZIP with the local Python runtime files."""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build the XueXue PaddleOCR sidecar artifact")
    parser.add_argument("version")
    parser.add_argument("output", type=Path)
    parser.add_argument(
        "--backend",
        default=os.getenv("XUEXUE_PADDLEOCR_BACKEND", "cpu"),
        choices=("cpu", "cuda118", "cuda126", "cuda129", "intel_gpu"),
        help="Paddle wheel variant to install into the artifact",
    )
    parser.add_argument(
        "--requirements",
        type=Path,
        help="Optional requirements file override for a custom Paddle backend",
    )
    parser.add_argument(
        "--custom-device-wheel",
        type=Path,
        action="append",
        default=[],
        help="A locally built Paddle custom-device wheel to install into the artifact",
    )
    return parser.parse_args()


def copy_entry(source: Path, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)


def make_executable(path: Path) -> None:
    if os.name != "nt":
        path.chmod(path.stat().st_mode | stat.S_IXUSR)


def artifact_digest(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    args = parse_args()
    if not args.version.strip():
        raise SystemExit("version must not be blank")
    custom_device_wheels = [wheel.resolve() for wheel in args.custom_device_wheel]
    missing_wheels = [wheel for wheel in custom_device_wheels if not wheel.is_file()]
    if missing_wheels:
        raise SystemExit(f"custom-device wheel does not exist: {missing_wheels[0]}")
    if args.backend == "intel_gpu" and not custom_device_wheels:
        raise SystemExit("--backend intel_gpu requires --custom-device-wheel")
    root = Path(__file__).resolve().parent
    default_requirements = root / "requirements.txt" if args.backend == "cpu" else root / f"requirements-{args.backend}.txt"
    requirements = (args.requirements or default_requirements).resolve()
    if not requirements.is_file():
        raise SystemExit(f"requirements file does not exist: {requirements}")
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="xuexue-paddleocr-sidecar-") as temporary:
        stage = Path(temporary)
        lib = stage / "lib"
        subprocess.run(
            [
                sys.executable,
                "-m",
                "pip",
                "install",
                "--no-compile",
                "--target",
                str(lib),
                "-r",
                str(requirements),
            ],
            check=True,
        )
        if custom_device_wheels:
            subprocess.run(
                [
                    sys.executable,
                    "-m",
                    "pip",
                    "install",
                    "--no-compile",
                    "--target",
                    str(lib),
                    *(str(wheel) for wheel in custom_device_wheels),
                ],
                check=True,
            )
        copy_entry(root / "paddleocr_sidecar.py", stage / "paddleocr_sidecar.py")
        copy_entry(root / "bin" / "paddleocr-sidecar", stage / "bin" / "paddleocr-sidecar")
        copy_entry(root / "bin" / "paddleocr-sidecar.cmd", stage / "bin" / "paddleocr-sidecar.cmd")
        make_executable(stage / "bin" / "paddleocr-sidecar")
        (stage / "VERSION").write_text(args.version + "\n", encoding="utf-8")
        (stage / "BACKEND").write_text(args.backend + "\n", encoding="utf-8")

        temporary_output = output.with_suffix(output.suffix + ".part")
        temporary_output.unlink(missing_ok=True)
        try:
            with zipfile.ZipFile(temporary_output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                for entry in sorted(stage.rglob("*")):
                    if entry.is_file():
                        archive.write(entry, entry.relative_to(stage).as_posix())
            temporary_output.replace(output)
        finally:
            temporary_output.unlink(missing_ok=True)

    print(f"version={args.version}")
    print(f"backend={args.backend}")
    print(f"sha256={artifact_digest(output)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
