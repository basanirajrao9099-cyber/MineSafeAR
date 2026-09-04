#!/usr/bin/env python3
"""Generate the placeholder .glb models the AR layer places on detected planes.

These exist because MineSafeAR needs *some* valid glTF binaries to build training
content against before real assets are authored, and vendoring binary blobs with
no provenance is worse than generating them reproducibly.

Everything is built from two primitives -- an axis-aligned box and a vertically
extruded convex polygon -- composed into multi-material meshes. Nothing here is
art. The one thing the shapes do take seriously is *colour coding*, because
telling a CO2 extinguisher from a water one is the actual skill the fire module
is testing, and in the real world you do that by the colour of the body.

Every model's origin is at the centre of its base, so it rests on a detected
plane instead of sinking half-way through it. Proportions are authored so the
longest edge is the dimension `scaleToUnits` should set -- see ArModels.kt.

Usage:
    python3 tools/generate_placeholder_models.py app/src/main/res/raw

Delete this script once real assets land in res/raw/ -- see ArModels.kt for the
list of models that replace each one.
"""

from __future__ import annotations

import json
import struct
import sys
from pathlib import Path

# glTF 2.0 component / target constants.
FLOAT = 5126
UNSIGNED_SHORT = 5123
ARRAY_BUFFER = 34962
ELEMENT_ARRAY_BUFFER = 34963
TRIANGLES = 4

# GLB container constants.
GLB_MAGIC = 0x46546C67  # "glTF"
GLB_VERSION = 2
CHUNK_JSON = 0x4E4F534A  # "JSON"
CHUNK_BIN = 0x004E4942  # "BIN\0"

Vec3 = tuple[float, float, float]

# --- Palette --------------------------------------------------------------
# baseColorFactor is linear, not sRGB. These are eyeballed rather than
# converted, which is fine for placeholders and wrong for real assets.

SAFETY_ORANGE = (0.98, 0.45, 0.05)
EXTINGUISHER_RED = (0.72, 0.05, 0.04)  # water, per IS 15683 / EN 3 body colour
EXTINGUISHER_CREAM = (0.91, 0.79, 0.45)  # foam
EXTINGUISHER_BLACK = (0.06, 0.06, 0.07)  # CO2 -- dark grey, not true black, so
#                                          it stays readable in a dim mine
DARK_METAL = (0.16, 0.16, 0.18)
LABEL_WHITE = (0.93, 0.93, 0.92)
SAFETY_GREEN = (0.02, 0.45, 0.20)  # exit signage and route markings
POST_GREY = (0.35, 0.36, 0.38)


def material(name: str, colour: Vec3, roughness: float = 0.55) -> dict:
    return {
        "name": name,
        "pbrMetallicRoughness": {
            "baseColorFactor": [colour[0], colour[1], colour[2], 1.0],
            "metallicFactor": 0.0,
            "roughnessFactor": roughness,
        },
        # Double-sided so a model is visible even if a viewer disagrees with our
        # winding order. Real assets should be single-sided.
        "doubleSided": True,
    }


# --- Geometry primitives --------------------------------------------------


class Part:
    """One primitive of a mesh: its own geometry and its own material."""

    def __init__(self, material_json: dict) -> None:
        self.material = material_json
        self.positions: list[Vec3] = []
        self.normals: list[Vec3] = []
        self.indices: list[int] = []

    def _quad(self, normal: Vec3, corners: list[Vec3]) -> None:
        """Append one flat quad as two triangles, with vertices duplicated so the
        face gets a hard edge rather than a smooth one."""
        first = len(self.positions)
        self.positions.extend(corners)
        self.normals.extend([normal] * 4)
        self.indices.extend([first, first + 1, first + 2, first, first + 2, first + 3])

    def box(self, lo: Vec3, hi: Vec3) -> "Part":
        """Axis-aligned box from `lo` to `hi`, wound counter-clockwise outwards."""
        x0, y0, z0 = lo
        x1, y1, z1 = hi
        self._quad((0.0, 1.0, 0.0), [(x0, y1, z0), (x0, y1, z1), (x1, y1, z1), (x1, y1, z0)])
        self._quad((0.0, -1.0, 0.0), [(x0, y0, z1), (x0, y0, z0), (x1, y0, z0), (x1, y0, z1)])
        self._quad((0.0, 0.0, 1.0), [(x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)])
        self._quad((0.0, 0.0, -1.0), [(x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0)])
        self._quad((1.0, 0.0, 0.0), [(x1, y0, z1), (x1, y0, z0), (x1, y1, z0), (x1, y1, z1)])
        self._quad((-1.0, 0.0, 0.0), [(x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0)])
        return self

    def extrude(self, polygon_xz: list[tuple[float, float]], y0: float, y1: float) -> "Part":
        """Extrude a *convex* polygon vertically from `y0` to `y1`.

        The polygon must be wound counter-clockwise in the (x, z) plane, which
        makes `(dz, 0, -dx)` the outward normal of the edge a -> b. Concave
        shapes (the exit arrow) are composed from several convex parts rather
        than triangulated properly, because ear clipping is not worth writing
        for six placeholder models.
        """
        count = len(polygon_xz)

        # Caps: fan-triangulated, which is only valid because the polygon is convex.
        for normal, y, flip in (((0.0, 1.0, 0.0), y1, False), ((0.0, -1.0, 0.0), y0, True)):
            first = len(self.positions)
            self.positions.extend([(px, y, pz) for px, pz in polygon_xz])
            self.normals.extend([normal] * count)
            for i in range(1, count - 1):
                triangle = [first, first + i, first + i + 1]
                self.indices.extend(reversed(triangle) if flip else triangle)

        # Sides.
        for i in range(count):
            ax, az = polygon_xz[i]
            bx, bz = polygon_xz[(i + 1) % count]
            dx, dz = bx - ax, bz - az
            length = (dx * dx + dz * dz) ** 0.5
            if length == 0.0:
                continue
            normal = (dz / length, 0.0, -dx / length)
            self._quad(normal, [(ax, y0, az), (bx, y0, bz), (bx, y1, bz), (ax, y1, az)])
        return self


# --- Model definitions ----------------------------------------------------
#
# Proportions matter, absolute size does not: `scaleToUnits` at placement time
# rescales the longest edge. Each model notes which edge that is.


def cube() -> list[Part]:
    """1x1x1, the original AR-pipeline smoke test. Longest edge: any (1.0)."""
    return [Part(material("SafetyOrange", SAFETY_ORANGE)).box((-0.5, 0.0, -0.5), (0.5, 1.0, 0.5))]


def extinguisher(name: str, body: Vec3) -> list[Part]:
    """A stubby canister with a valve, handle and hose. Longest edge: height (1.0).

    Body colour is the whole point -- it is how a trainee is meant to identify
    the class -- so it is the caller's parameter and everything else is fixed.
    """
    return [
        Part(material(f"{name}Body", body)).box((-0.16, 0.0, -0.16), (0.16, 0.72, 0.16)),
        # A white label band around the body, so the class colour still reads as
        # a colour rather than as "the dark object" under bad lighting.
        Part(material("Label", LABEL_WHITE, roughness=0.8)).box(
            (-0.17, 0.30, -0.17), (0.17, 0.50, 0.17)
        ),
        Part(material("Hardware", DARK_METAL, roughness=0.35))
        .box((-0.07, 0.72, -0.07), (0.07, 0.86, 0.07))  # valve neck
        .box((-0.11, 0.86, -0.05), (0.11, 0.95, 0.05))  # squeeze handle
        .box((0.16, 0.18, -0.045), (0.32, 0.27, 0.045)),  # hose stub
    ]


def exit_sign() -> list[Part]:
    """A panel on a post. Longest edge: height (1.85).

    Real signage is wall-mounted; this is floor-standing because the pipeline is
    horizontal-plane-only for now. See ArModels.kt.
    """
    return [
        Part(material("Post", POST_GREY, roughness=0.4))
        .box((-0.14, 0.0, -0.14), (0.14, 0.03, 0.14))  # base plate
        .box((-0.03, 0.03, -0.03), (0.03, 1.55, 0.03)),  # post
        Part(material("SignFace", SAFETY_GREEN, roughness=0.7)).box(
            (-0.30, 1.55, -0.02), (0.30, 1.85, 0.02)
        ),
        # Stands in for the running-man pictogram, proud of the face on both
        # sides so it is legible from either direction.
        Part(material("Pictogram", LABEL_WHITE, roughness=0.8)).box(
            (-0.24, 1.66, -0.03), (0.24, 1.74, 0.03)
        ),
    ]


def exit_arrow() -> list[Part]:
    """A flat chevron lying on the floor, pointing +Z. Longest edge: length (1.0).

    All three arrows in the fire module use this same model on purpose: a decoy
    that looks different from the correct route is not a decoy.
    """
    thickness = (0.0, 0.035)
    shaft = [(-0.13, -0.50), (0.13, -0.50), (0.13, 0.10), (-0.13, 0.10)]
    head = [(-0.34, 0.10), (0.34, 0.10), (0.0, 0.50)]
    return [
        Part(material("RouteGreen", SAFETY_GREEN, roughness=0.7))
        .extrude(shaft, *thickness)
        .extrude(head, *thickness)
    ]


MODELS: dict[str, tuple[str, list[Part]]] = {}


def register() -> None:
    MODELS["placeholder_cube"] = ("PlaceholderCube", cube())
    MODELS["placeholder_extinguisher_co2"] = (
        "ExtinguisherCO2",
        extinguisher("CO2", EXTINGUISHER_BLACK),
    )
    MODELS["placeholder_extinguisher_foam"] = (
        "ExtinguisherFoam",
        extinguisher("Foam", EXTINGUISHER_CREAM),
    )
    MODELS["placeholder_extinguisher_water"] = (
        "ExtinguisherWater",
        extinguisher("Water", EXTINGUISHER_RED),
    )
    MODELS["placeholder_exit_sign"] = ("ExitSign", exit_sign())
    MODELS["placeholder_exit_arrow"] = ("ExitArrow", exit_arrow())


# --- GLB assembly ---------------------------------------------------------


def pad(data: bytes, alignment: int = 4, filler: bytes = b"\x00") -> bytes:
    remainder = len(data) % alignment
    return data if remainder == 0 else data + filler * (alignment - remainder)


def build_glb(name: str, parts: list[Part]) -> bytes:
    """Pack `parts` into one mesh: shared POSITION/NORMAL accessors, one index
    accessor and one material per part."""
    positions: list[Vec3] = []
    normals: list[Vec3] = []
    indices: list[int] = []
    index_ranges: list[tuple[int, int]] = []  # (first index, count) per part

    for part in parts:
        offset = len(positions)
        positions.extend(part.positions)
        normals.extend(part.normals)
        index_ranges.append((len(indices), len(part.indices)))
        indices.extend(i + offset for i in part.indices)

    if len(positions) > 0xFFFF:
        raise ValueError(f"{name}: {len(positions)} vertices exceeds uint16 indices")

    position_bytes = b"".join(struct.pack("<3f", *v) for v in positions)
    normal_bytes = b"".join(struct.pack("<3f", *v) for v in normals)
    index_bytes = b"".join(struct.pack("<H", i) for i in indices)

    # Every bufferView offset must satisfy its component's alignment; padding to
    # 4 covers both float32 and uint16.
    position_offset = 0
    normal_offset = position_offset + len(pad(position_bytes))
    index_offset = normal_offset + len(pad(normal_bytes))
    binary = pad(position_bytes) + pad(normal_bytes) + pad(index_bytes)

    accessors: list[dict] = [
        {
            "bufferView": 0,
            "componentType": FLOAT,
            "count": len(positions),
            "type": "VEC3",
            # POSITION accessors are the only ones where min/max is required.
            "min": [min(v[axis] for v in positions) for axis in range(3)],
            "max": [max(v[axis] for v in positions) for axis in range(3)],
        },
        {
            "bufferView": 1,
            "componentType": FLOAT,
            "count": len(normals),
            "type": "VEC3",
        },
    ]
    primitives: list[dict] = []
    for part_index, (first, count) in enumerate(index_ranges):
        primitives.append(
            {
                "attributes": {"POSITION": 0, "NORMAL": 1},
                "indices": len(accessors),
                "material": part_index,
                "mode": TRIANGLES,
            }
        )
        accessors.append(
            {
                "bufferView": 2,
                # uint16, so a whole number of indices is always 2-byte aligned.
                "byteOffset": first * 2,
                "componentType": UNSIGNED_SHORT,
                "count": count,
                "type": "SCALAR",
            }
        )

    gltf = {
        "asset": {
            "version": "2.0",
            "generator": "MineSafeAR tools/generate_placeholder_models.py",
        },
        "scene": 0,
        "scenes": [{"name": f"{name}Scene", "nodes": [0]}],
        "nodes": [{"name": name, "mesh": 0}],
        "meshes": [{"name": name, "primitives": primitives}],
        "materials": [part.material for part in parts],
        "accessors": accessors,
        "bufferViews": [
            {
                "buffer": 0,
                "byteOffset": position_offset,
                "byteLength": len(position_bytes),
                "target": ARRAY_BUFFER,
            },
            {
                "buffer": 0,
                "byteOffset": normal_offset,
                "byteLength": len(normal_bytes),
                "target": ARRAY_BUFFER,
            },
            {
                "buffer": 0,
                "byteOffset": index_offset,
                "byteLength": len(index_bytes),
                "target": ELEMENT_ARRAY_BUFFER,
            },
        ],
        "buffers": [{"byteLength": len(binary)}],
    }

    # JSON chunk pads with spaces, BIN chunk pads with zeroes (glTF 2.0 spec).
    json_bytes = pad(json.dumps(gltf, separators=(",", ":")).encode("utf-8"), filler=b" ")
    bin_bytes = pad(binary)

    total = 12 + 8 + len(json_bytes) + 8 + len(bin_bytes)
    return b"".join(
        [
            struct.pack("<III", GLB_MAGIC, GLB_VERSION, total),
            struct.pack("<II", len(json_bytes), CHUNK_JSON),
            json_bytes,
            struct.pack("<II", len(bin_bytes), CHUNK_BIN),
            bin_bytes,
        ]
    )


def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} <output-dir>", file=sys.stderr)
        return 2

    register()
    out_dir = Path(sys.argv[1])
    out_dir.mkdir(parents=True, exist_ok=True)

    for file_stem, (mesh_name, parts) in MODELS.items():
        glb = build_glb(mesh_name, parts)
        destination = out_dir / f"{file_stem}.glb"
        destination.write_bytes(glb)
        triangles = sum(len(p.indices) for p in parts) // 3
        print(f"wrote {destination} ({len(glb)} bytes, {triangles} triangles)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
