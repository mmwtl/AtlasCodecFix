#!/usr/bin/env python3

from __future__ import annotations

import json
from pathlib import Path
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
PROFILE_KEYS = {"codecs", "performance", "profiles", "specs"}


def strip_json_comments(text: str) -> str:
    output: list[str] = []
    index = 0
    in_string = False
    escaped = False
    while index < len(text):
        char = text[index]
        next_char = text[index + 1] if index + 1 < len(text) else ""
        if in_string:
            output.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            index += 1
            continue
        if char == '"':
            in_string = True
            output.append(char)
            index += 1
            continue
        if char == "/" and next_char == "/":
            index += 2
            while index < len(text) and text[index] not in "\r\n":
                index += 1
            continue
        if char == "/" and next_char == "*":
            index += 2
            while index + 1 < len(text) and text[index : index + 2] != "*/":
                index += 1
            index += 2
            continue
        output.append(char)
        index += 1
    return "".join(output)


def parse_manifest(path: Path) -> dict[str, str]:
    entries: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, filename = line.partition("=")
        assert separator and key in PROFILE_KEYS, f"Invalid manifest line in {path}: {raw_line}"
        assert key not in entries, f"Duplicate manifest key {key} in {path}"
        assert filename and "/" not in filename, f"Unsafe manifest filename in {path}: {filename}"
        assert (path.parent / filename).is_file(), f"Missing manifest source {path.parent / filename}"
        entries[key] = filename
    assert entries, f"Empty manifest: {path}"
    return entries


def active_codecs(path: Path) -> set[str]:
    root = ET.parse(path).getroot()
    names = [element.attrib["name"] for element in root.iter("MediaCodec")]
    assert len(names) == len(set(names)), f"Duplicate active codec name in {path}"
    return set(names)


def effective_identifiers(path: Path, seen: set[Path] | None = None) -> dict[str, set[str]]:
    seen = set() if seen is None else seen
    path = path.resolve()
    if path in seen:
        return {}
    seen.add(path)

    root = ET.parse(path).getroot()
    owners: dict[str, set[str]] = {}
    for codec in root.iter("MediaCodec"):
        owner = codec.attrib["name"]
        identifiers = [owner, *(alias.attrib["name"] for alias in codec.findall("Alias"))]
        for identifier in identifiers:
            owners.setdefault(identifier, set()).add(owner)

    for include in root.findall("Include"):
        included = path.parent / include.attrib["href"]
        if not included.exists():
            included = (ROOT / "default" / include.attrib["href"]).resolve()
        if not included.exists() or included in seen:
            continue

        for identifier, included_owners in effective_identifiers(included, seen).items():
            owners.setdefault(identifier, set()).update(included_owners)

    return owners


def effective_identifier_conflicts(path: Path) -> set[str]:
    return {
        identifier
        for identifier, codec_owners in effective_identifiers(path).items()
        if len(codec_owners) > 1
    }


def main() -> None:
    xml_files = sorted(ROOT.rglob("*.xml"))
    json_files = sorted(ROOT.rglob("*.json"))
    assert xml_files and json_files

    for path in xml_files:
        ET.parse(path)
    for path in json_files:
        json.loads(strip_json_comments(path.read_text(encoding="utf-8")))

    manifests = {
        path.parent.name: parse_manifest(path)
        for path in sorted(ROOT.glob("*/profile.manifest"))
    }
    assert manifests["min"] == {"codecs": "media_codecs_min.xml"}
    assert set(manifests["max"]) == PROFILE_KEYS
    assert set(manifests["ultra"]) == PROFILE_KEYS

    min_dir = ROOT / "min"
    default_dir = ROOT / "default"
    identical_pairs = [
        (min_dir / "media_codecs_performance_min.xml", default_dir / "media_codecs_performance_msmnile.xml"),
        (min_dir / "media_profiles_min.xml", default_dir / "media_profiles_msmnile.xml"),
        (min_dir / "video_system_specs_min.json", default_dir / "media_msmnile/video_system_specs.json"),
    ]
    for derived, reference in identical_pairs:
        assert derived.read_bytes() == reference.read_bytes(), f"Unexpected Min drift: {derived}"

    msmnile = active_codecs(default_dir / "media_codecs_msmnile.xml")
    minimum = active_codecs(min_dir / "media_codecs_min.xml")
    expected_hevc = {
        "c2.qti.hevc.decoder",
        "c2.qti.hevc.decoder.secure",
        "c2.qti.hevc.encoder",
        "c2.qti.hevc.encoder.cq",
    }
    assert not msmnile - minimum, "Min removed active msmnile codecs"
    assert minimum - msmnile == expected_hevc, "Min contains an unexpected active codec delta"

    for path in [
        default_dir / "media_codecs_msmnile.xml",
        default_dir / "media_codecs_direwolf.xml",
        min_dir / "media_codecs_min.xml",
        ROOT / "max/media_codecs_max.xml",
        ROOT / "ultra/media_codecs_ultra.xml",
    ]:
        active_codecs(path)

    baseline_conflicts = effective_identifier_conflicts(default_dir / "media_codecs_msmnile.xml")
    min_conflicts = effective_identifier_conflicts(min_dir / "media_codecs_min.xml")
    max_conflicts = effective_identifier_conflicts(ROOT / "max/media_codecs_max.xml")
    ultra_conflicts = effective_identifier_conflicts(ROOT / "ultra/media_codecs_ultra.xml")
    assert not min_conflicts - baseline_conflicts, "Min introduced new effective name/alias conflicts"
    assert not max_conflicts - baseline_conflicts, "Max introduced new effective name/alias conflicts"
    assert not ultra_conflicts, "Ultra effective include tree has active name/alias conflicts"

    # Keep the current Max provenance explicit until it is replaced by a measured msmnile-only profile.
    assert (ROOT / "max/media_profiles_max.xml").read_bytes() == (
        default_dir / "media_profiles_direwolf.xml"
    ).read_bytes()

    print(f"validated {len(xml_files)} XML, {len(json_files)} JSONC and {len(manifests)} manifests")


if __name__ == "__main__":
    main()
