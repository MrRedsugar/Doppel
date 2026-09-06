import io
import os
import secrets
import zipfile
from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException, UploadFile
from fastapi.responses import FileResponse

from .gateway import CheckedRoute

MAX_BYTES = 20 * 1024 * 1024


def document_folder(runtime, owner):
    folder = runtime.config.data_dir / "documents" / owner
    folder.mkdir(parents=True, exist_ok=True)
    return folder.resolve()


def document_path(folder, name):
    if not name or Path(name).name != name or "\\" in name or "/" in name or ":" in name or len(name) > 160 or not name.lower().endswith(".xlsx"):
        raise HTTPException(422, "Only a simple .xlsx file name is allowed")
    path = (folder / name).resolve()
    if path.parent != folder:
        raise HTTPException(422, "File escapes its authorized folder")
    return path


def entry(path):
    return {"name": path.name, "size": path.stat().st_size, "download_uri": "doppel-document://" + path.name}


def create_document_router(runtime, owner_dependency):
    router = APIRouter(route_class=CheckedRoute)

    @router.get("/documents")
    def files(owner=Depends(owner_dependency)):
        folder = document_folder(runtime, owner)
        return {"items": [entry(p) for p in sorted(folder.glob("*.xlsx")) if p.is_file() and not p.is_symlink()]}

    @router.post("/documents", status_code=201)
    async def upload(file: UploadFile, owner=Depends(owner_dependency)):
        folder = document_folder(runtime, owner)
        output = document_path(folder, file.filename)
        if output.exists():
            raise HTTPException(409, "A file with that name already exists")
        data = await file.read(MAX_BYTES + 1)
        await file.close()
        if len(data) > MAX_BYTES:
            raise HTTPException(413, "File exceeds 20 MB")
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                infos = archive.infolist()
                if len(infos) > 5000 or sum(i.file_size for i in infos) > 200 * 1024 * 1024:
                    raise HTTPException(413, "Expanded workbook is too large")
                if "xl/workbook.xml" not in archive.namelist() or any("vbaproject" in i.filename.lower() for i in infos):
                    raise HTTPException(422, "Unsupported workbook contents")
        except zipfile.BadZipFile:
            raise HTTPException(422, "Not a valid XLSX file") from None
        temporary = folder / ("." + secrets.token_hex(16) + ".upload")
        claimed = False
        try:
            with temporary.open("xb") as handle:
                handle.write(data)
            # An exclusive destination claim prevents concurrent imports overwriting data.
            descriptor = os.open(output, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            os.close(descriptor)
            claimed = True
            os.replace(temporary, output)
            claimed = False
        except FileExistsError:
            raise HTTPException(409, "A file with that name already exists") from None
        finally:
            temporary.unlink(missing_ok=True)
            if claimed:
                output.unlink(missing_ok=True)
        return entry(output)

    @router.get("/documents/{name}")
    def download(name: str, owner=Depends(owner_dependency)):
        path = document_path(document_folder(runtime, owner), name)
        if not path.is_file():
            raise HTTPException(404, "Document not found")
        return FileResponse(path, filename=path.name, media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")

    @router.delete("/documents/{name}")
    def delete(name: str, owner=Depends(owner_dependency)):
        path = document_path(document_folder(runtime, owner), name)
        if not path.is_file():
            raise HTTPException(404, "Document not found")
        path.unlink()
        return {"ok": True}

    return router
