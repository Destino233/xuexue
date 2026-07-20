#!/usr/bin/env python3
"""Small Poppler-independent verifier for the outline objects in a PDF fixture."""

import json
import re
import sys
from pathlib import Path


OBJECT_RE = re.compile(rb"(?m)(\d+)\s+0\s+obj\s*(.*?)\s*endobj", re.DOTALL)
REF_RE = rb"/(%s)\s+(\d+)\s+0\s+R"


def objects(pdf: bytes) -> dict[int, bytes]:
    return {int(match.group(1)): match.group(2) for match in OBJECT_RE.finditer(pdf)}


def reference(body: bytes, key: bytes) -> int | None:
    match = re.search(REF_RE % key, body)
    return int(match.group(2)) if match else None


def references_in_array(body: bytes, key: bytes) -> list[int]:
    match = re.search(rb"/" + key + rb"\s*\[(.*?)\]", body, re.DOTALL)
    if not match:
        return []
    return [int(value) for value in re.findall(rb"(\d+)\s+0\s+R", match.group(1))]


def title(body: bytes) -> str:
    match = re.search(rb"/Title\s*\(((?:\\.|[^)])*)\)", body, re.DOTALL)
    if not match:
        return ""
    value = match.group(1)
    value = re.sub(rb"\\([()\\])", rb"\1", value)
    return value.decode("utf-8", errors="replace")


def page_reference(body: bytes) -> int | None:
    destination = re.search(rb"/Dest\s*\[\s*(\d+)\s+0\s+R", body)
    if destination:
        return int(destination.group(1))
    uri = re.search(rb"/URI\s*\(\#page=(\d+)\)", body)
    return int(uri.group(1)) - 1 if uri else None


def page_objects(objects_by_id: dict[int, bytes], root_id: int | None) -> dict[int, int]:
    result: dict[int, int] = {}
    visited: set[int] = set()

    def visit(object_id: int) -> None:
        if object_id in visited or object_id not in objects_by_id:
            return
        visited.add(object_id)
        body = objects_by_id[object_id]
        if re.search(rb"/Type\s*/Page(?:\s|>)", body):
            result[object_id] = len(result)
            return
        for child in references_in_array(body, b"Kids"):
            visit(child)

    if root_id is not None:
        visit(root_id)
    return result


def outline_tree(
    objects_by_id: dict[int, bytes],
    first_id: int | None,
    pages: dict[int, int],
) -> list[dict]:
    visited: set[int] = set()

    def siblings(object_id: int | None) -> list[dict]:
        result = []
        current = object_id
        while current is not None and current not in visited:
            visited.add(current)
            body = objects_by_id[current]
            target = page_reference(body)
            result.append(
                {
                    "title": title(body),
                    "page_index": pages.get(target, target),
                    "children": siblings(reference(body, b"First")),
                }
            )
            current = reference(body, b"Next")
        return result

    return siblings(first_id)


def verify(path: Path) -> list[dict]:
    pdf = path.read_bytes()
    parsed = objects(pdf)
    trailer = re.search(rb"trailer\s*<<.*?/Root\s+(\d+)\s+0\s+R", pdf, re.DOTALL)
    if not trailer:
        raise ValueError("PDF trailer has no catalog root")
    catalog_id = int(trailer.group(1))
    catalog = parsed[catalog_id]
    pages = page_objects(parsed, reference(catalog, b"Pages"))
    outlines_id = reference(catalog, b"Outlines")
    if outlines_id is None:
        return []
    return outline_tree(parsed, reference(parsed[outlines_id], b"First"), pages)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: verify_pdf_outline.py FILE.pdf")
    print(json.dumps(verify(Path(sys.argv[1])), ensure_ascii=False, sort_keys=True))
