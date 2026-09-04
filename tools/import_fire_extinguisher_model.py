#!/usr/bin/env python3
"""Convert external Fire Extinguisher 3D model archive (.zip) into glTF 2.0 binary (.glb) for AR.

Extracts the Collada (.dae) mesh and PBR texture maps (Albedo, Metallic, Roughness, Normal, AO),
combines metallic & roughness texture channels per the glTF 2.0 specification, aligns the base
pivot at (0, 0, 0) for ground plane anchoring, and outputs `app/src/main/res/raw/realistic_extinguisher.glb`.
"""

from __future__ import annotations

import os
import sys
import tempfile
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image
import trimesh

DEFAULT_ZIP_PATH = r"C:\Users\ayush\Downloads\fire-extinguisher.zip"


def convert_model(zip_path: str, output_glb_path: str, texture_res: int = 1024) -> None:
    zip_file = Path(zip_path)
    if not zip_file.exists():
        raise FileNotFoundError(f"Input zip file not found at: {zip_path}")

    out_file = Path(output_glb_path)
    out_file.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory() as tmp_dir:
        tmp_path = Path(tmp_dir)
        print(f"Extracting outer zip: {zip_file}")
        with zipfile.ZipFile(zip_file, "r") as zf:
            zf.extractall(tmp_path)

        model_zip = tmp_path / "source" / "model.zip"
        if not model_zip.exists():
            # Check if model.zip is in root
            model_zip = tmp_path / "model.zip"

        model_extract_dir = tmp_path / "model_extracted"
        if model_zip.exists():
            print(f"Extracting inner model zip: {model_zip}")
            with zipfile.ZipFile(model_zip, "r") as zf:
                zf.extractall(model_extract_dir)
        else:
            model_extract_dir = tmp_path

        # Find .dae file
        dae_files = list(model_extract_dir.rglob("*.dae"))
        if not dae_files:
            raise FileNotFoundError("No .dae model file found in archive")

        dae_path = dae_files[0]
        tex_dir = dae_path.parent / "textures"
        if not tex_dir.exists():
            tex_dir = tmp_path / "textures"

        print(f"Loading Collada mesh from {dae_path}...")
        mesh = trimesh.load(dae_path)
        geom = list(mesh.geometry.values())[0]

        # Center base at X=0, Y=0 (bottom), Z=0
        bounds = geom.bounds
        cx = (bounds[0][0] + bounds[1][0]) / 2.0
        cz = (bounds[0][2] + bounds[1][2]) / 2.0
        min_y = bounds[0][1]

        geom.vertices[:, 0] -= cx
        geom.vertices[:, 1] -= min_y
        geom.vertices[:, 2] -= cz

        print(f"Mesh vertices: {len(geom.vertices)}, faces: {len(geom.faces)}")
        print(f"Centered bounds: {geom.bounds}")

        # Load & resize PBR texture maps
        albedo_path = next(tex_dir.glob("*albedo*"), None)
        metallic_path = next(tex_dir.glob("*metallic*"), None)
        roughness_path = next(tex_dir.glob("*roughness*"), None)
        normal_path = next(tex_dir.glob("*normal*"), None)
        ao_path = next(tex_dir.glob("*AO*"), None) or next(tex_dir.glob("*ao*"), None)

        albedo_img = Image.open(albedo_path).resize((texture_res, texture_res), Image.LANCZOS) if albedo_path else None
        m_img = Image.open(metallic_path).convert("L").resize((texture_res, texture_res), Image.LANCZOS) if metallic_path else None
        r_img = Image.open(roughness_path).convert("L").resize((texture_res, texture_res), Image.LANCZOS) if roughness_path else None
        normal_img = Image.open(normal_path).resize((texture_res, texture_res), Image.LANCZOS) if normal_path else None
        ao_img = Image.open(ao_path).resize((texture_res, texture_res), Image.LANCZOS) if ao_path else None

        mr_img = None
        if m_img is not None and r_img is not None:
            m_arr = np.array(m_img)
            r_arr = np.array(r_img)
            mr_arr = np.stack([np.zeros_like(r_arr), r_arr, m_arr], axis=-1)
            mr_img = Image.fromarray(mr_arr)

        pbr = trimesh.visual.material.PBRMaterial(
            name="ExtinguisherPBR",
            baseColorTexture=albedo_img,
            metallicRoughnessTexture=mr_img,
            normalTexture=normal_img,
            occlusionTexture=ao_img,
            doubleSided=True,
        )

        geom.visual.material = pbr
        scene = trimesh.Scene(geom)

        print("Exporting GLB...")
        glb_bytes = scene.export(file_type="glb")

        out_file.write_bytes(glb_bytes)
        print(f"Saved glTF binary to: {out_file} ({len(glb_bytes)} bytes / {len(glb_bytes)/1024/1024:.2f} MB)")


if __name__ == "__main__":
    zip_arg = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_ZIP_PATH
    project_root = Path(__file__).resolve().parent.parent
    out_arg = str(project_root / "app" / "src" / "main" / "res" / "raw" / "realistic_extinguisher.glb")
    convert_model(zip_arg, out_arg)
