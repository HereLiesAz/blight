"""Upload thumbnails to a public Google Drive folder."""
from __future__ import annotations
import io
from PIL import Image

PUBLIC_URL_TEMPLATE = "https://drive.google.com/uc?id={file_id}"


def public_url_for_id(file_id: str) -> str:
    return PUBLIC_URL_TEMPLATE.format(file_id=file_id)


def compress_thumbnail(jpeg_bytes: bytes, *, max_dim: int = 256, quality: int = 70) -> bytes:
    """Re-encode a JPEG (or any PIL-decodable image) as a quality-controlled thumbnail."""
    img = Image.open(io.BytesIO(jpeg_bytes)).convert('RGB')
    w, h = img.size
    if w > max_dim or h > max_dim:
        if w >= h:
            new_w, new_h = max_dim, round(h * max_dim / w)
        else:
            new_w, new_h = round(w * max_dim / h), max_dim
        img = img.resize((new_w, new_h), Image.LANCZOS)
    out = io.BytesIO()
    img.save(out, 'JPEG', quality=quality, optimize=True)
    return out.getvalue()


class DriveUploader:
    """Idempotent uploader: searches folder for existing <panoid>.jpg, otherwise creates and shares public."""

    def __init__(self, credentials, folder_id: str):
        from googleapiclient.discovery import build
        self._drive = build('drive', 'v3', credentials=credentials, cache_discovery=False)
        self.folder_id = folder_id

    def upload(self, panoid: str, jpeg_bytes: bytes) -> str:
        """Upload (or reuse) <panoid>.jpg in the folder; return public URL."""
        name = f"{panoid}.jpg"
        q = f"'{self.folder_id}' in parents and name = '{name}' and trashed = false"
        found = self._drive.files().list(q=q, fields="files(id)").execute().get('files', [])
        if found:
            return public_url_for_id(found[0]['id'])

        from googleapiclient.http import MediaIoBaseUpload
        media = MediaIoBaseUpload(io.BytesIO(jpeg_bytes), mimetype='image/jpeg')
        created = self._drive.files().create(
            body={'name': name, 'parents': [self.folder_id]},
            media_body=media,
            fields='id',
        ).execute()
        file_id = created['id']
        self._drive.permissions().create(
            fileId=file_id,
            body={'type': 'anyone', 'role': 'reader'},
        ).execute()
        return public_url_for_id(file_id)
