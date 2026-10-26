from __future__ import annotations

import io
import math
import os
import re
import shutil
import subprocess
import tempfile
import json
import asyncio
import uuid
from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse, Response
from PIL import Image, ImageDraw, ImageFont, ImageOps

try:
    import pillow_heif

    pillow_heif.register_heif_opener()
    pillow_heif.register_avif_opener()
except ImportError:
    pass

ROOT = Path(__file__).parent
MAX_UPLOAD = int(os.getenv("MAX_UPLOAD_MB", "512")) * 1024 * 1024
JOBS = Path(tempfile.gettempdir()) / "cchong-jobs"
app = FastAPI(title="Cchong Watermark", docs_url=None, redoc_url=None)


@app.get("/")
def home():
    return FileResponse(ROOT / "static" / "index.html")


@app.get("/health")
def health():
    return {"ok": True, "ffmpeg": bool(shutil.which("ffmpeg"))}


@app.get("/{asset}")
def static(asset: str):
    if asset not in {"style.css", "app.js", "icon.svg", "manifest.webmanifest"}:
        raise HTTPException(404)
    return FileResponse(ROOT / "static" / asset)


@app.get("/fonts/{asset}")
def font_asset(asset: str):
    if asset not in {"Manrope.ttf", "CormorantGaramond.ttf"}:
        raise HTTPException(404)
    return FileResponse(ROOT / "fonts" / asset)


def font(size: int, family="manrope", weight=400):
    selected = ROOT / "fonts" / ("CormorantGaramond.ttf" if family == "cormorant" else "Manrope.ttf")
    if selected.exists():
        face = ImageFont.truetype(str(selected), size)
        face.set_variation_by_axes([weight])
        return face
    paths = [
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "C:/Windows/Fonts/arial.ttf",
    ]
    for path in paths:
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def rational(value):
    try:
        if isinstance(value, (tuple, list)) and len(value) == 2:
            return float(value[0]) / float(value[1])
        return float(value)
    except (TypeError, ValueError, ZeroDivisionError):
        return None


def gps_string(exif):
    try:
        gps = exif.get_ifd(0x8825)
        lat, lon = gps.get(2), gps.get(4)
        if not lat or not lon:
            return None
        def decimal(parts):
            return sum(rational(v) / 60 ** i for i, v in enumerate(parts))
        latitude = decimal(lat) * (-1 if gps.get(1) in ("S", b"S") else 1)
        longitude = decimal(lon) * (-1 if gps.get(3) in ("W", b"W") else 1)
        return f"{latitude:.4f}°, {longitude:.4f}°"
    except (AttributeError, TypeError, ValueError, ZeroDivisionError):
        return None


def metadata(image: Image.Image):
    exif = image.getexif()
    camera = exif.get_ifd(0x8769) if exif.get(0x8769) else exif
    result = []
    iso = camera.get(34855) or exif.get(34855)
    exposure = rational(camera.get(33434) or exif.get(33434))
    aperture = rational(camera.get(33437) or exif.get(33437))
    if iso:
        result.append(f"ISO {iso}")
    if exposure and exposure > 0:
        result.append(f"{exposure:.1f}s" if exposure >= 1 else f"1/{round(1 / exposure)}s")
    if aperture:
        result.append(f"f/{aperture:g}")
    if len(result) < 3:
        gps = gps_string(exif)
        if gps:
            result.append(gps)
    if len(result) < 3:
        result.append(f"{image.width} × {image.height}")
    return "   ·   ".join(result[:3])


def rgb_histogram_points(image: Image.Image, bins=128):
    sample = image.copy()
    sample.thumbnail((512, 512), Image.Resampling.NEAREST)
    hist = sample.convert("RGB").histogram()
    channels = [
        [sum(hist[channel * 256 + i * 256 // bins:channel * 256 + (i + 1) * 256 // bins]) for i in range(bins)]
        for channel in range(3)
    ]
    peak = max(max(channel) for channel in channels) or 1
    # Log scaling keeps quiet tones visible without smoothing away narrow peaks.
    return [[math.log1p(30 * count / peak) / math.log1p(30) for count in channel] for channel in channels]


DEFAULT_SETTINGS = {"overall": 1.0, "histogram_width": 1.0, "histogram_height": 1.0,
                    "metadata": 1.0, "signature": 1.0, "x": 0.5, "y": 0.83}
SETTING_RANGES = {"overall": (0.5, 1.6), "histogram_width": (0.5, 1.5),
                  "histogram_height": (0.5, 1.8), "metadata": (0.6, 1.6),
                  "signature": (0.6, 1.6), "x": (0.0, 1.0), "y": (0.0, 1.0)}


def parse_settings(raw: str):
    try:
        supplied = json.loads(raw)
        if not isinstance(supplied, dict):
            raise ValueError()
        settings = DEFAULT_SETTINGS.copy()
        for key, (low, high) in SETTING_RANGES.items():
            value = float(supplied.get(key, settings[key]))
            if not math.isfinite(value) or not low <= value <= high:
                raise ValueError()
            settings[key] = value
        return settings
    except (TypeError, ValueError, json.JSONDecodeError):
        raise HTTPException(400, "无效的水印设置")


def watermark_overlay(image: Image.Image, color_mode: str, settings=None, original_dimensions=None):
    settings = settings or DEFAULT_SETTINGS
    width, height = image.size
    short = min(width, height)
    scale = settings["overall"]
    hist_w = max(1, round(short * .30 * scale * settings["histogram_width"]))
    hist_h = max(12, round(short * .087 * scale * settings["histogram_height"]))
    meta_size = max(8, round(short * .017 * scale * settings["metadata"]))
    meta_font = font(meta_size, weight=500)
    brand_font = font(max(10, round(short * .028 * scale * settings["signature"])), family="cormorant", weight=600)
    gap = max(3, round(short * .007 * scale))
    brand_gap = max(2, round(short * .003 * scale))
    meta_h = round(short * .024 * scale * settings["metadata"])
    brand_h = round(short * .035 * scale * settings["signature"])
    block_h = hist_h + gap + meta_h + brand_gap + brand_h
    center_x = max(hist_w / 2, min(width - hist_w / 2, round(width * settings["x"])))
    hist_x = round(center_x - hist_w / 2)
    y0 = max(0, min(height - block_h, round(height * settings["y"] - block_h / 2)))
    if color_mode == "auto":
        sample_width = max(hist_w, round(short * .48 * scale))
        sample_x = max(0, min(width - sample_width, round(center_x - sample_width / 2)))
        crop = ImageOps.grayscale(image.crop((sample_x, y0, sample_x + sample_width, y0 + block_h)))
        avg = sum(crop.resize((32, 16)).getdata()) / (32 * 16)
        color_mode = "black" if avg >= 135 else "white"
    color = (0, 0, 0, 255) if color_mode == "black" else (255, 255, 255, 255)
    overlay = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    channels = rgb_histogram_points(image)
    axis = max(2, round(short * .0022))
    curve = max(1, round(short * .001))
    baseline = hist_h - axis
    outlines = []
    for values, opacity in zip(channels, (45, 75, 105)):
        points = [(round(i * (hist_w - 1) / (len(values) - 1)), baseline - round(value * max(1, hist_h - 3 * axis) * .94)) for i, value in enumerate(values)]
        layer = Image.new("RGBA", (hist_w, hist_h), (0, 0, 0, 0))
        ImageDraw.Draw(layer).polygon([(0, baseline), *points, (hist_w - 1, baseline)], fill=(*color[:3], opacity))
        overlay.alpha_composite(layer, (hist_x, y0))
        outlines.append(points)
    for points, opacity in zip(outlines, (145, 190, 235)):
        layer = Image.new("RGBA", (hist_w, hist_h), (0, 0, 0, 0))
        ImageDraw.Draw(layer).line(points, fill=(*color[:3], opacity), width=curve)
        overlay.alpha_composite(layer, (hist_x, y0))
    draw = ImageDraw.Draw(overlay)
    draw.line((hist_x, y0 + baseline, hist_x + hist_w, y0 + baseline), fill=color, width=axis)
    meta = metadata(image)
    if original_dimensions and "×" in meta:
        meta = meta.replace(f"{width} × {height}", f"{original_dimensions[0]} × {original_dimensions[1]}")
    meta_y = y0 + hist_h + gap
    while meta_size > 8 and draw.textbbox((0, 0), meta, font=meta_font)[2] > hist_w * .94:
        meta_size -= 1
        meta_font = font(meta_size, weight=500)
    draw.text((center_x, meta_y), meta, fill=color, font=meta_font, anchor="mt")
    draw.text((center_x, meta_y + meta_h + brand_gap), "@Cchong", fill=color, font=brand_font, anchor="mt")
    return overlay


def still_image(data: bytes, color: str, output_format="JPEG", xmp=None, settings=None):
    with Image.open(io.BytesIO(data)) as source:
        source.load()
        exif = source.getexif()
        image = ImageOps.exif_transpose(source).convert("RGB")
        exif[274] = 1
        image.info["exif"] = exif.tobytes()
        overlay = watermark_overlay(image, color, settings)
        merged = Image.alpha_composite(image.convert("RGBA"), overlay).convert("RGB")
        out = io.BytesIO()
        if output_format == "JPEG":
            merged.save(out, format="JPEG", quality=94, subsampling=0, exif=exif.tobytes())
        else:
            merged.save(out, format=output_format, quality=90, exif=exif.tobytes(), xmp=xmp)
        return out.getvalue(), overlay


def jpeg_primary_end(data: bytes):
    if not data.startswith(b"\xff\xd8"):
        return None
    pos = 2
    while pos < len(data) - 1:
        if data[pos] != 0xff:
            return None
        marker = data[pos + 1]
        if marker == 0xda:
            end = data.find(b"\xff\xd9", pos + 2)
            return end + 2 if end >= 0 else None
        if marker == 0xd9:
            return pos + 2
        if marker in (0x01,) or 0xd0 <= marker <= 0xd7:
            pos += 2
            continue
        if pos + 4 > len(data):
            return None
        length = int.from_bytes(data[pos + 2:pos + 4], "big")
        if length < 2:
            return None
        pos += length + 2
    return None


def split_motion_jpeg(data: bytes):
    end = jpeg_primary_end(data)
    if not end or end >= len(data):
        return None
    tail = data[end:]
    lengths = re.findall(rb'(?:Item:Length|GContainer:ItemLength)=["\'](\d+)', data[:end])
    for raw_length in reversed(lengths):
        length = int(raw_length)
        if 0 < length <= len(tail):
            candidate = data[-length:]
            if len(candidate) >= 8 and candidate[4:8] == b"ftyp":
                start = len(data) - length
                gainmap = data[end:start] if b'Item:Semantic="GainMap"' in data[:end] else b""
                return data[:end], candidate, gainmap
    for i in range(max(0, len(tail) - 7)):
        if tail[i + 4:i + 8] == b"ftyp":
            size = int.from_bytes(tail[i:i + 4], "big")
            if 8 <= size <= len(tail) - i:
                gainmap = tail[:i] if b'Item:Semantic="GainMap"' in data[:end] else b""
                return data[:end], tail[i:], gainmap
    return None


def split_motion_isobmff(data: bytes):
    if len(data) < 16 or data[4:8] != b"ftyp":
        return None
    pos = 0
    while pos + 8 <= len(data):
        size = int.from_bytes(data[pos:pos + 4], "big")
        kind = data[pos + 4:pos + 8]
        header = 8
        if size == 1:
            if pos + 16 > len(data):
                return None
            size = int.from_bytes(data[pos + 8:pos + 16], "big")
            header = 16
        if size < header or pos + size > len(data):
            return None
        if kind == b"mpvd" and pos + size == len(data):
            return data[:pos], data[pos + header:pos + size], b""
        pos += size
    return None


def motion_xmp(mime: str, video_length: int, padding=0, gainmap_length=0, camera_prefix="Camera", timestamp=-1):
    pad = f' Item:Padding="{padding}"' if padding else ""
    gainmap = f'<rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="GainMap" Item:Length="{gainmap_length}"/></rdf:li>' if gainmap_length else ""
    return f'''<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:{camera_prefix}="http://ns.google.com/photos/1.0/camera/" {camera_prefix}:MotionPhoto="1" {camera_prefix}:MotionPhotoVersion="1" {camera_prefix}:MotionPhotoPresentationTimestampUs="{timestamp}"/><rdf:Description rdf:about="" xmlns:Container="http://ns.google.com/photos/1.0/container/" xmlns:Item="http://ns.google.com/photos/1.0/container/item/"><Container:Directory><rdf:Seq><rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="{mime}" Item:Semantic="Primary"{pad}/></rdf:li>{gainmap}<rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="{video_length}"/></rdf:li></rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>'''.encode()


def add_motion_xmp(jpeg: bytes, video_length: int, gainmap_length=0, camera_prefix="Camera", timestamp=-1):
    payload = b"http://ns.adobe.com/xap/1.0/\x00" + motion_xmp("image/jpeg", video_length, gainmap_length=gainmap_length, camera_prefix=camera_prefix, timestamp=timestamp)
    segment = b"\xff\xe1" + (len(payload) + 2).to_bytes(2, "big") + payload
    return jpeg[:2] + segment + jpeg[2:]


def process_motion(still: bytes, video: bytes, gainmap: bytes, color: str, workdir: Path, image_format="JPEG", settings=None):
    output_still, overlay = still_image(still, color, settings=settings)
    overlay_path = workdir / "overlay.png"
    video_path = workdir / "input.mp4"
    result_path = workdir / "output.mp4"
    video_path.write_bytes(video)
    probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height", "-of", "json", str(video_path)], capture_output=True, text=True, timeout=30)
    if probe.returncode:
        raise ValueError("无法读取动态照片中的视频")
    stream = json.loads(probe.stdout)["streams"][0]
    overlay.resize((stream["width"], stream["height"]), Image.Resampling.LANCZOS).save(overlay_path)
    command = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(video_path), "-i", str(overlay_path), "-filter_complex", "[0:v][1:v]overlay=0:0:format=auto[v]", "-map", "[v]", "-map", "0:a?", "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p", "-c:a", "copy", "-movflags", "+faststart", str(result_path)]
    done = subprocess.run(command, capture_output=True, text=True, timeout=300)
    if done.returncode:
        raise ValueError("动态视频处理失败：" + done.stderr[-250:])
    output_video = result_path.read_bytes()
    if image_format == "JPEG":
        prefix = "GCamera" if b"GCamera:MotionPhoto" in still else "Camera"
        match = re.search(rb'MotionPhotoPresentationTimestampUs=["\'](-?\d+)', still)
        timestamp = int(match.group(1)) if match else -1
        return add_motion_xmp(output_still, len(output_video), len(gainmap), prefix, timestamp) + gainmap + output_video
    mime = "image/heic" if image_format == "HEIF" else "image/avif"
    xmp = motion_xmp(mime, len(output_video), padding=8)
    encoded_still, _ = still_image(still, color, image_format, xmp=xmp, settings=settings)
    box_length = 8 + len(output_video)
    if box_length >= 2 ** 32:
        raise ValueError("动态视频超过容器大小限制")
    return encoded_still + box_length.to_bytes(4, "big") + b"mpvd" + output_video


def safe_name(name: str):
    name = Path(name.replace("\\", "/")).name
    return re.sub(r"[^\w.\- ]", "_", name)[:120] or "photo"


@app.post("/api/preview")
async def preview(file: UploadFile = File(...), color: str = Form("auto"), settings: str = Form("{}"), original: bool = Form(False)):
    if color not in {"auto", "black", "white"}:
        raise HTTPException(400, "无效的水印颜色")
    options = parse_settings(settings)
    data = await file.read(MAX_UPLOAD + 1)
    if len(data) > MAX_UPLOAD:
        raise HTTPException(413, "照片过大")
    try:
        with Image.open(io.BytesIO(data)) as source:
            source.load()
            image = ImageOps.exif_transpose(source).convert("RGB")
            image.info["exif"] = source.getexif().tobytes()
            dimensions = image.size
            image.thumbnail((1200, 1200), Image.Resampling.LANCZOS)
            if original:
                merged = image
            else:
                overlay = watermark_overlay(image, color, options, dimensions)
                merged = Image.alpha_composite(image.convert("RGBA"), overlay).convert("RGB")
            out = io.BytesIO()
            merged.save(out, format="JPEG", quality=86)
            motion = bool(split_motion_jpeg(data) or split_motion_isobmff(data))
            return Response(out.getvalue(), media_type="image/jpeg", headers={"X-Motion-Photo": "1" if motion else "0"})
    except Exception as error:
        raise HTTPException(400, f"无法预览照片：{str(error)[:100]}")


@app.post("/api/motion-preview")
async def motion_preview(file: UploadFile = File(...), color: str = Form("auto"),
                         settings: str = Form("{}"), part: str = Form("video")):
    if color not in {"auto", "white", "black"} or part not in {"video", "overlay"}:
        raise HTTPException(400, "无效的预览参数")
    options = parse_settings(settings)
    data = await file.read(MAX_UPLOAD + 1)
    if len(data) > MAX_UPLOAD:
        raise HTTPException(413, "照片过大")
    motion = split_motion_jpeg(data) or split_motion_isobmff(data)
    if not motion:
        raise HTTPException(404, "这张照片没有可播放的视频")
    still, video, _ = motion
    if part == "video":
        return Response(video, media_type="video/mp4")
    try:
        with Image.open(io.BytesIO(still)) as source:
            source.load()
            image = ImageOps.exif_transpose(source).convert("RGB")
            dimensions = image.size
            image.info["exif"] = source.getexif().tobytes()
            image.thumbnail((1200, 1200), Image.Resampling.LANCZOS)
            overlay = watermark_overlay(image, color, options, dimensions)
            out = io.BytesIO()
            overlay.save(out, format="PNG")
            return Response(out.getvalue(), media_type="image/png")
    except Exception as error:
        raise HTTPException(400, f"无法预览动态照片：{str(error)[:100]}")


async def cleanup_job(job_dir: Path):
    await asyncio.sleep(3600)
    shutil.rmtree(job_dir, ignore_errors=True)


@app.get("/api/download/{job_id}/{filename}")
def download(job_id: str, filename: str):
    if not re.fullmatch(r"[0-9a-f]{32}", job_id) or safe_name(filename) != filename:
        raise HTTPException(404)
    target = JOBS / job_id / filename
    if not target.is_file():
        raise HTTPException(404)
    return FileResponse(target, media_type="application/octet-stream", filename=filename)


@app.post("/api/process")
async def process(files: list[UploadFile] = File(...), color: str = Form("auto"), settings: str = Form("{}")):
    settings = parse_settings(settings)
    if color not in {"auto", "black", "white"}:
        raise HTTPException(400, "无效颜色")
    if len(files) > 100:
        raise HTTPException(400, "每批最多 100 张")
    job_id = uuid.uuid4().hex
    job_dir = JOBS / job_id
    job_dir.mkdir(parents=True, mode=0o700)
    results = []
    errors = []
    used = set()
    for upload in files:
        name = safe_name(upload.filename or "photo")
        data = await upload.read(MAX_UPLOAD + 1)
        if len(data) > MAX_UPLOAD:
            errors.append(f"{name}: 文件超过大小限制")
            continue
        try:
            with tempfile.TemporaryDirectory() as temp:
                motion = split_motion_jpeg(data) or split_motion_isobmff(data)
                if motion:
                    extension = Path(name).suffix.lower()
                    image_format = "HEIF" if extension in {".heic", ".heif"} else "AVIF" if extension == ".avif" else "JPEG"
                    output = process_motion(*motion, color, Path(temp), image_format, settings)
                    suffix = ".heic" if image_format == "HEIF" else ".avif" if image_format == "AVIF" else ".jpg"
                    out_name = Path(name).stem + "_CchongMP" + suffix
                else:
                    if b"MotionPhoto" in data or (name.upper().startswith("MVIMG_") and jpeg_primary_end(data) and jpeg_primary_end(data) < len(data)):
                        raise ValueError("检测到动态照片，但暂时无法读取视频；未导出静态图")
                    output, _ = still_image(data, color, settings=settings)
                    out_name = Path(name).stem + "_Cchong.jpg"
                unique = out_name
                count = 2
                while unique in used:
                    unique = f"{Path(out_name).stem}_{count}{Path(out_name).suffix}"
                    count += 1
                used.add(unique)
                (job_dir / unique).write_bytes(output)
                results.append({"original": name, "name": unique, "url": f"/api/download/{job_id}/{unique}", "motion": bool(motion)})
        except Exception as exc:
            errors.append(f"{name}: {str(exc)[:200]}")
    asyncio.create_task(cleanup_job(job_dir))
    return {"items": results, "errors": errors, "expires_in_seconds": 3600}
