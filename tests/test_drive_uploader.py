from io import BytesIO
from PIL import Image

from scripts.lib.drive_uploader import compress_thumbnail, public_url_for_id


def _make_jpeg(width, height, color=(200, 100, 50), quality=95):
    img = Image.new('RGB', (width, height), color)
    buf = BytesIO()
    img.save(buf, 'JPEG', quality=quality)
    return buf.getvalue()


def test_compress_thumbnail_produces_jpeg_bytes_under_50kb():
    raw = _make_jpeg(1024, 1024)
    out = compress_thumbnail(raw, max_dim=256, quality=70)
    assert out.startswith(b'\xff\xd8\xff'), 'JPEG magic'
    assert len(out) < 50_000, f'expected <50KB, got {len(out)}'


def test_compress_thumbnail_preserves_aspect_ratio_for_non_square():
    raw = _make_jpeg(1024, 512)
    out = compress_thumbnail(raw, max_dim=256, quality=70)
    img = Image.open(BytesIO(out))
    # Wider than tall input -> width hits max_dim, height scales down proportionally
    assert img.size == (256, 128), f'expected (256, 128) got {img.size}'


def test_compress_thumbnail_does_not_upscale_small_inputs():
    raw = _make_jpeg(100, 100)
    out = compress_thumbnail(raw, max_dim=256, quality=70)
    img = Image.open(BytesIO(out))
    assert img.size == (100, 100), f'expected unchanged 100x100 got {img.size}'


def test_public_url_for_id_returns_uc_form():
    assert public_url_for_id("FILE123") == "https://drive.google.com/uc?id=FILE123"
