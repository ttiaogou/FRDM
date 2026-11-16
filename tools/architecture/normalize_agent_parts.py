#!/usr/bin/env python3
"""Restore the canonical AGENTS part filenames from the byte-verified escaped copies.

The escaped filenames were created by a path-encoding mistake. Their contents are the
protected source fragments; they must be validated against AGENTS.original.md before any
existing canonical file is overwritten or any duplicate is removed.
"""
from __future__ import annotations

import argparse
from pathlib import Path

PARTS = (
    (
        "00-#U6700#U9ad8#U6267#U884c#U539f#U5219#U4e0e#U9879#U76ee#U8303#U56f4.md",
        "00-最高执行原则与项目范围.md",
    ),
    (
        "01-#U603b#U4f53#U67b6#U6784#U4e0e#U76ee#U6807#U76ee#U5f55.md",
        "01-总体架构与目标目录.md",
    ),
    (
        "02-#U67b6#U6784#U8fb9#U754c#U4e0e#U72b6#U6001#U6240#U6709#U6743.md",
        "02-架构边界与状态所有权.md",
    ),
    (
        "03-#U670d#U52a1#U751f#U547d#U5468#U671f#U4e0e#U4eba#U5de5#U63a5#U7ba1.md",
        "03-服务生命周期与人工接管.md",
    ),
    (
        "04-#U8282#U70b9#U68c0#U7d22#U624b#U52bf#U4e0e#U7a7a#U95f4#U5750#U6807.md",
        "04-节点检索手势与空间坐标.md",
    ),
    (
        "05-#U65f6#U95f4#U8282#U5f8b#U6ed1#U52a8#U8d77#U6b62#U70b9#U4e0e#U8f93#U5165#U63a7#U5236.md",
        "05-时间节律滑动起止点与输入控制.md",
    ),
    (
        "06-#U9632#U91cd#U590d#U624b#U52bf#U53ef#U89c6#U5316#U4e0eAI#U56de#U590d.md",
        "06-防重复手势可视化与AI回复.md",
    ),
    (
        "07-#U65e5#U5fd7#U6d4b#U8bd5#U53d8#U66f4#U7eaa#U5f8b#U4e0e#U9a8c#U6536.md",
        "07-日志测试变更纪律与验收.md",
    ),
)


def normalize(root: Path) -> None:
    parts_dir = root / "docs/agent/parts"
    archive_path = root / "docs/agent/source/AGENTS.original.md"
    if not archive_path.is_file() or not parts_dir.is_dir():
        raise ValueError("AGENTS source archive or parts directory is missing")

    archive = archive_path.read_bytes()
    fragments: list[tuple[Path, Path, bytes]] = []
    for escaped_name, canonical_name in PARTS:
        escaped = parts_dir / escaped_name
        if not escaped.is_file():
            raise ValueError(f"Missing byte-verified source fragment: {escaped_name}")
        fragments.append((escaped, parts_dir / canonical_name, escaped.read_bytes()))

    # Validate the entire source before changing or deleting any file.
    if b"".join(content for _, _, content in fragments) != archive:
        raise ValueError("Escaped source fragments do not reconstruct AGENTS.original.md; no files changed")

    for escaped, canonical, content in fragments:
        canonical.write_bytes(content)
        escaped.unlink()

    final_parts = sorted(parts_dir.glob("*.md"))
    expected_names = [canonical for _, canonical in PARTS]
    if [path.name for path in final_parts] != expected_names:
        raise ValueError("Canonical AGENTS part set is not exactly 00-07 after normalization")
    if b"".join(path.read_bytes() for path in final_parts) != archive:
        raise ValueError("Canonical AGENTS parts failed final byte-for-byte verification")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    args = parser.parse_args()
    try:
        normalize(args.root.resolve())
    except ValueError as error:
        parser.error(str(error))
    print("AGENTS fragments normalized: exactly 8 canonical parts; byte-for-byte match confirmed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
