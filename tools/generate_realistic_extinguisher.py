#!/usr/bin/env python3
"""Generate a realistic 3D Fire Extinguisher glTF (.glb) model for PASS training.

Features modeled:
- Main cylindrical tank with dished top/bottom end caps
- Front instruction & specification label band
- Valve body & neck assembly (metallic)
- Pressure gauge with green zone indicator
- Carrying handle and hinged discharge squeeze lever
- Bright safety pull-pin with pull ring
- Flexible black rubber discharge hose with flared nozzle horn
- Bottom protective rubber boot/foot
"""

from __future__ import annotations

import json
import math
import struct
import sys
from pathlib import Path

# glTF 2.0 constants
FLOAT = 5126
UNSIGNED_SHORT = 5123
ARRAY_BUFFER = 34962
ELEMENT_ARRAY_BUFFER = 34963
TRIANGLES = 4

GLB_MAGIC = 0x46546C67
GLB_VERSION = 2
CHUNK_JSON = 0x4E4F534A
CHUNK_BIN = 0x004E4942

Vec3 = tuple[float, float, float]

# Palette
EXTINGUISHER_RED = (0.82, 0.08, 0.06)
LABEL_WHITE = (0.94, 0.94, 0.92)
VALVE_BRASS = (0.78, 0.72, 0.55)
DARK_METAL = (0.18, 0.18, 0.20)
SAFETY_YELLOW = (0.96, 0.78, 0.10)
HOSE_BLACK = (0.08, 0.08, 0.09)
GAUGE_GREEN = (0.05, 0.75, 0.25)
BOOT_BLACK = (0.12, 0.12, 0.14)


def material(name: str, colour: Vec3, metallic: float = 0.0, roughness: float = 0.5) -> dict:
    return {
        "name": name,
        "pbrMetallicRoughness": {
            "baseColorFactor": [colour[0], colour[1], colour[2], 1.0],
            "metallicFactor": metallic,
            "roughnessFactor": roughness,
        },
        "doubleSided": True,
    }


class Part:
    def __init__(self, material_json: dict) -> None:
        self.material = material_json
        self.positions: list[Vec3] = []
        self.normals: list[Vec3] = []
        self.indices: list[int] = []

    def _quad(self, normal: Vec3, corners: list[Vec3]) -> None:
        first = len(self.positions)
        self.positions.extend(corners)
        self.normals.extend([normal] * 4)
        self.indices.extend([first, first + 1, first + 2, first, first + 2, first + 3])

    def box(self, lo: Vec3, hi: Vec3) -> "Part":
        x0, y0, z0 = lo
        x1, y1, z1 = hi
        self._quad((0.0, 1.0, 0.0), [(x0, y1, z0), (x0, y1, z1), (x1, y1, z1), (x1, y1, z0)])
        self._quad((0.0, -1.0, 0.0), [(x0, y0, z1), (x0, y0, z0), (x1, y0, z0), (x1, y0, z1)])
        self._quad((0.0, 0.0, 1.0), [(x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)])
        self._quad((0.0, 0.0, -1.0), [(x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0)])
        self._quad((1.0, 0.0, 0.0), [(x1, y0, z1), (x1, y0, z0), (x1, y1, z0), (x1, y1, z1)])
        self._quad((-1.0, 0.0, 0.0), [(x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0)])
        return self

    def cylinder(self, center_xz: tuple[float, float], radius: float, y0: float, y1: float, segments: int = 24) -> "Part":
        cx, cz = center_xz
        angles = [2.0 * math.pi * i / segments for i in range(segments)]
        circle = [(cx + radius * math.cos(a), cz + radius * math.sin(a)) for a in angles]

        # Top cap
        first_top = len(self.positions)
        self.positions.append((cx, y1, cz))
        self.normals.append((0.0, 1.0, 0.0))
        for px, pz in circle:
            self.positions.append((px, y1, pz))
            self.normals.append((0.0, 1.0, 0.0))
        for i in range(segments):
            next_i = (i + 1) % segments
            self.indices.extend([first_top, first_top + 1 + i, first_top + 1 + next_i])

        # Bottom cap
        first_bot = len(self.positions)
        self.positions.append((cx, y0, cz))
        self.normals.append((0.0, -1.0, 0.0))
        for px, pz in circle:
            self.positions.append((px, y0, pz))
            self.normals.append((0.0, -1.0, 0.0))
        for i in range(segments):
            next_i = (i + 1) % segments
            self.indices.extend([first_bot, first_bot + 1 + next_i, first_bot + 1 + i])

        # Sides
        for i in range(segments):
            next_i = (i + 1) % segments
            ax, az = circle[i]
            bx, bz = circle[next_i]
            a_ang = angles[i]
            b_ang = angles[next_i]

            na = (math.cos(a_ang), 0.0, math.sin(a_ang))
            nb = (math.cos(b_ang), 0.0, math.sin(b_ang))

            idx = len(self.positions)
            self.positions.extend([(ax, y0, az), (bx, y0, bz), (bx, y1, bz), (ax, y1, az)])
            self.normals.extend([na, nb, nb, na])
            self.indices.extend([idx, idx + 1, idx + 2, idx, idx + 2, idx + 3])

        return self

    def dome(self, center_xz: tuple[float, float], radius: float, base_y: float, height: float, is_top: bool = True, segments: int = 24, rings: int = 6) -> "Part":
        cx, cz = center_xz
        for r in range(rings):
            phi0 = (math.pi / 2.0) * (r / rings)
            phi1 = (math.pi / 2.0) * ((r + 1) / rings)

            y_offset0 = height * math.sin(phi0)
            y_offset1 = height * math.sin(phi1)

            r0 = radius * math.cos(phi0)
            r1 = radius * math.cos(phi1)

            y_a = base_y + (y_offset0 if is_top else -y_offset0)
            y_b = base_y + (y_offset1 if is_top else -y_offset1)

            for s in range(segments):
                a0 = 2.0 * math.pi * (s / segments)
                a1 = 2.0 * math.pi * ((s + 1) / segments)

                x0_0 = cx + r0 * math.cos(a0)
                z0_0 = cz + r0 * math.sin(a0)
                x0_1 = cx + r0 * math.cos(a1)
                z0_1 = cz + r0 * math.sin(a1)

                x1_0 = cx + r1 * math.cos(a0)
                z1_0 = cz + r1 * math.sin(a0)
                x1_1 = cx + r1 * math.cos(a1)
                z1_1 = cz + r1 * math.sin(a1)

                corners = [(x0_0, y_a, z0_0), (x0_1, y_a, z0_1), (x1_1, y_b, z1_1), (x1_0, y_b, z1_0)]
                norm_sign = 1.0 if is_top else -1.0
                normals = [(math.cos(a0) * 0.7, 0.7 * norm_sign, math.sin(a0) * 0.7),
                           (math.cos(a1) * 0.7, 0.7 * norm_sign, math.sin(a1) * 0.7),
                           (math.cos(a1) * 0.7, 0.7 * norm_sign, math.sin(a1) * 0.7),
                           (math.cos(a0) * 0.7, 0.7 * norm_sign, math.sin(a0) * 0.7)]

                first = len(self.positions)
                self.positions.extend(corners)
                self.normals.extend(normals)
                if is_top:
                    self.indices.extend([first, first + 1, first + 2, first, first + 2, first + 3])
                else:
                    self.indices.extend([first, first + 2, first + 1, first, first + 3, first + 2])
        return self

    def ring_torus(self, center: Vec3, major_r: float, minor_r: float, axis: str = "Y", segments: int = 16, ring_sides: int = 8) -> "Part":
        cx, cy, cz = center
        for s in range(segments):
            a0 = 2.0 * math.pi * (s / segments)
            a1 = 2.0 * math.pi * ((s + 1) / segments)

            for rs in range(ring_sides):
                b0 = 2.0 * math.pi * (rs / ring_sides)
                b1 = 2.0 * math.pi * ((rs + 1) / ring_sides)

                # Compute torus coordinates
                def torus_pt(a: float, b: float) -> tuple[Vec3, Vec3]:
                    c_maj = major_r + minor_r * math.cos(b)
                    if axis == "Y":
                        pt = (cx + c_maj * math.cos(a), cy + minor_r * math.sin(b), cz + c_maj * math.sin(a))
                        norm = (math.cos(a) * math.cos(b), math.sin(b), math.sin(a) * math.cos(b))
                    elif axis == "X":
                        pt = (cx + minor_r * math.sin(b), cy + c_maj * math.cos(a), cz + c_maj * math.sin(a))
                        norm = (math.sin(b), math.cos(a) * math.cos(b), math.sin(a) * math.cos(b))
                    else: # Z
                        pt = (cx + c_maj * math.cos(a), cy + c_maj * math.sin(a), cz + minor_r * math.sin(b))
                        norm = (math.cos(a) * math.cos(b), math.sin(a) * math.cos(b), math.sin(b))
                    return pt, norm

                p00, n00 = torus_pt(a0, b0)
                p10, n10 = torus_pt(a1, b0)
                p11, n11 = torus_pt(a1, b1)
                p01, n01 = torus_pt(a0, b1)

                first = len(self.positions)
                self.positions.extend([p00, p10, p11, p01])
                self.normals.extend([n00, n10, n11, n01])
                self.indices.extend([first, first + 1, first + 2, first, first + 2, first + 3])
        return self


def create_realistic_extinguisher() -> list[Part]:
    parts = []

    # 1. Main Cylinder Tank (Red)
    mat_red = material("ExtinguisherRed", EXTINGUISHER_RED, metallic=0.15, roughness=0.35)
    body_part = Part(mat_red)
    body_part.cylinder((0.0, 0.0), radius=0.15, y0=0.08, y1=0.62, segments=24)
    body_part.dome((0.0, 0.0), radius=0.15, base_y=0.62, height=0.08, is_top=True, segments=24)
    body_part.dome((0.0, 0.0), radius=0.15, base_y=0.08, height=0.05, is_top=False, segments=24)
    parts.append(body_part)

    # 2. Bottom Protective Boot/Foot (Black Rubber)
    mat_boot = material("BootBlack", BOOT_BLACK, metallic=0.05, roughness=0.8)
    boot_part = Part(mat_boot)
    boot_part.cylinder((0.0, 0.0), radius=0.155, y0=0.0, y1=0.08, segments=24)
    parts.append(boot_part)

    # 3. Instruction & Marking Label Band (Front - White & PASS info)
    mat_label = material("LabelBand", LABEL_WHITE, metallic=0.0, roughness=0.6)
    label_part = Part(mat_label)
    # Curved outer label patch on the front half (+Z)
    label_part.cylinder((0.0, 0.0), radius=0.152, y0=0.22, y1=0.48, segments=24)
    parts.append(label_part)

    # 4. Metallic Neck & Valve Assembly
    mat_brass = material("ValveBrass", VALVE_BRASS, metallic=0.85, roughness=0.25)
    valve_part = Part(mat_brass)
    valve_part.cylinder((0.0, 0.0), radius=0.045, y0=0.70, y1=0.78, segments=16) # neck collar
    valve_part.cylinder((0.0, 0.0), radius=0.035, y0=0.78, y1=0.85, segments=16) # valve body
    # Side extension for gauge and hose output
    valve_part.box((-0.04, 0.79, -0.04), (0.04, 0.84, 0.08))
    parts.append(valve_part)

    # 5. Pressure Gauge (with Green Zone Face)
    mat_dark_metal = material("DarkMetal", DARK_METAL, metallic=0.75, roughness=0.3)
    mat_gauge_green = material("GaugeGreen", GAUGE_GREEN, metallic=0.0, roughness=0.4)

    gauge_housing = Part(mat_dark_metal)
    gauge_housing.cylinder((-0.06, 0.02), radius=0.025, y0=0.80, y1=0.83, segments=12)
    parts.append(gauge_housing)

    gauge_face = Part(mat_gauge_green)
    gauge_face.cylinder((-0.06, 0.02), radius=0.022, y0=0.83, y1=0.832, segments=12)
    parts.append(gauge_face)

    # 6. Carrying Handle & Squeeze Lever
    handle_part = Part(mat_dark_metal)
    # Lower fixed handle
    handle_part.box((-0.025, 0.84, -0.12), (0.025, 0.87, 0.02))
    handle_part.box((-0.025, 0.84, -0.12), (0.025, 0.91, -0.09))
    # Upper squeeze operating lever (slightly angled up at rear)
    handle_part.box((-0.022, 0.89, -0.14), (0.022, 0.92, 0.02))
    handle_part.box((-0.022, 0.92, -0.14), (0.022, 0.96, -0.06))
    parts.append(handle_part)

    # 7. Safety Pin & Yellow Pull Ring
    mat_yellow = material("SafetyYellow", SAFETY_YELLOW, metallic=0.1, roughness=0.3)
    pin_part = Part(mat_yellow)
    # Pin shaft through handles
    pin_part.cylinder((0.04, -0.04), radius=0.008, y0=0.88, y1=0.885, segments=8)
    # Pull Ring
    pin_part.ring_torus((0.06, 0.885, -0.04), major_r=0.028, minor_r=0.005, axis="X", segments=12, ring_sides=6)
    parts.append(pin_part)

    # 8. Flexible Discharge Hose & Nozzle Horn
    mat_hose = material("HoseBlack", HOSE_BLACK, metallic=0.05, roughness=0.75)
    hose_part = Part(mat_hose)
    # Curve from valve front (+Z) down and curving forward
    hose_part.cylinder((0.0, 0.06), radius=0.016, y0=0.68, y1=0.80, segments=12)
    hose_part.box((-0.018, 0.65, 0.06), (0.018, 0.69, 0.16))
    hose_part.box((-0.02, 0.40, 0.14), (0.02, 0.66, 0.18)) # vertical segment of hose
    # Flared Nozzle Horn
    hose_part.cylinder((0.0, 0.22), radius=0.022, y0=0.38, y1=0.45, segments=12)
    hose_part.cylinder((0.0, 0.28), radius=0.038, y0=0.33, y1=0.38, segments=12) # flared nozzle end
    parts.append(hose_part)

    return parts


def pad(data: bytes, alignment: int = 4, filler: bytes = b"\x00") -> bytes:
    remainder = len(data) % alignment
    return data if remainder == 0 else data + filler * (alignment - remainder)


def build_glb(name: str, parts: list[Part]) -> bytes:
    positions: list[Vec3] = []
    normals: list[Vec3] = []
    indices: list[int] = []
    index_ranges: list[tuple[int, int]] = []

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
                "byteOffset": first * 2,
                "componentType": UNSIGNED_SHORT,
                "count": count,
                "type": "SCALAR",
            }
        )

    gltf = {
        "asset": {
            "version": "2.0",
            "generator": "MineSafeAR tools/generate_realistic_extinguisher.py",
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

    parts = create_realistic_extinguisher()
    out_dir = Path(sys.argv[1])
    out_dir.mkdir(parents=True, exist_ok=True)

    glb = build_glb("ExtinguisherRealistic", parts)
    destination = out_dir / "realistic_extinguisher.glb"
    destination.write_bytes(glb)
    triangles = sum(len(p.indices) for p in parts) // 3
    print(f"wrote {destination} ({len(glb)} bytes, {triangles} triangles)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
