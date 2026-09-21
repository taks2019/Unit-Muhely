# -*- coding: utf-8 -*-
"""Unity csomag motor. A Kotlin felület hívja Chaquopy-n keresztül.

Projekt mappa felépítése:
  original.<ext>   az eredeti csomag
  src/             kibontott tartalom (sosem módosul)
  export/          exportált szövegek, képek, hangok, index.json
  mods/            módosított fájlok (az APK-ba ezek kerülnek vissza)
  build/           elkészült csomag
"""
import os, sys, io, json, re, hashlib, shutil, struct, zipfile, types, base64, traceback


# --- Androidon hiányzó C-modulok helyettesítése, hogy a UnityPy betöltődjön ---
def _ensure_stub(name):
    try:
        __import__(name)
    except Exception:
        m = types.ModuleType(name)
        m.__stub__ = True

        def _ga(attr, _n=name):
            if attr.startswith("__"):
                raise AttributeError(attr)

            def _f(*a, **k):
                raise RuntimeError("%s nem érhető el ezen az eszközön" % _n)
            return _f
        m.__getattr__ = _ga
        sys.modules[name] = m


for _n in ("texture2ddecoder", "etcpak", "pyfmodex", "tabulate", "brotli"):
    _ensure_stub(_n)

HAVE_DECODER = not getattr(sys.modules["texture2ddecoder"], "__stub__", False)
HAVE_ENCODER = not getattr(sys.modules["etcpak"], "__stub__", False)

import UnityPy  # noqa: E402
from PIL import Image  # noqa: E402

UNITY_MAGIC = (b"UnityFS", b"UnityWeb", b"UnityRaw", b"UnityArchive")
UNITY_EXT = (".assets", ".bundle", ".unity3d", ".ab", ".assetbundle")
SKIP_EXT = (".ress", ".resource", ".so", ".dex", ".png", ".jpg", ".jpeg", ".ogg", ".mp3",
            ".wav", ".mp4", ".webm", ".ttf", ".otf", ".arsc", ".bin", ".dat", ".zip", ".apk")
PLAIN_EXT = (".txt", ".json", ".csv", ".tsv", ".xml", ".lua", ".ini", ".properties",
             ".yaml", ".yml", ".srt", ".strings", ".po", ".html", ".md")
SERIALIZED_NAME = re.compile(r"^(level\d+|sharedassets\d+\.assets|globalgamemanagers(\.assets)?|maindata|"
                             r"unity_builtin_extra|unity default resources|resources\.assets)$")


def _log(cb, msg):
    try:
        if cb is not None:
            cb.accept(str(msg))
        else:
            print(msg)
    except Exception:
        pass


def _rel(root, p):
    return os.path.relpath(p, root).replace(os.sep, "/")


def _sha(path):
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _safe(name, n=50):
    s = re.sub(r"[^\w\-. ]+", "_", str(name or ""), flags=re.UNICODE).strip(" .")
    return (s or "nevtelen")[:n]


def _write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)


def _walk(root):
    for dp, _dn, fn in os.walk(root):
        for f in sorted(fn):
            yield os.path.join(dp, f)


# ----------------------------------------------------------------- kibontás
def unpack(src_file, proj_dir, cb=None):
    src_dir = os.path.join(proj_dir, "src")
    shutil.rmtree(src_dir, ignore_errors=True)
    os.makedirs(src_dir, exist_ok=True)
    base = os.path.normpath(src_dir) + os.sep
    with zipfile.ZipFile(src_file) as z:
        infos = z.infolist()
        total = len(infos)
        for i, zi in enumerate(infos):
            dest = os.path.normpath(os.path.join(src_dir, zi.filename))
            if not dest.startswith(base):
                continue  # zip-slip védelem
            if zi.is_dir():
                os.makedirs(dest, exist_ok=True)
                continue
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with z.open(zi) as a, open(dest, "wb") as b:
                shutil.copyfileobj(a, b, 1 << 20)
            if i % 250 == 0:
                _log(cb, "Kibontás: %d / %d" % (i, total))
        names = [zi.filename for zi in infos]
    il2cpp = any(n.endswith("libil2cpp.so") for n in names)
    meta = [n for n in names if n.endswith("global-metadata.dat")]
    inner_apks = [n for n in names if n.lower().endswith(".apk") and "/" not in n]
    res = {"files": total, "il2cpp": il2cpp, "metadata": meta[0] if meta else "",
           "xapk": bool(inner_apks and "manifest.json" in names), "inner_apks": inner_apks}
    _log(cb, "Kész: %d fájl kibontva" % total)
    return json.dumps(res)


# ----------------------------------------------------------------- keresés
def _unity_kind(path):
    low = os.path.basename(path).lower()
    if low.endswith(SKIP_EXT) and not low.endswith(UNITY_EXT):
        return None
    try:
        with open(path, "rb") as f:
            head = f.read(16)
    except OSError:
        return None
    if head.startswith(UNITY_MAGIC):
        return "bundle"
    if SERIALIZED_NAME.match(low) or low.endswith(".assets"):
        return "serialized"
    if low.endswith(UNITY_EXT):
        return "ismeretlen"  # Unity-nak látszó kiterjesztés, de ismeretlen fejléc (titkosított?)
    return None


def _looks_text(data):
    if not data:
        return False
    if data[:3] == b"\xef\xbb\xbf" or data[:2] in (b"\xff\xfe", b"\xfe\xff"):
        return True
    return b"\x00" not in data[:2000]


def _decode(data):
    for enc in ("utf-8-sig", "utf-16", "cp1250"):
        try:
            return data.decode(enc)
        except Exception:
            pass
    return data.decode("utf-8", "replace")


def _strings(tree, out, depth=0):
    if depth > 40 or len(out) > 5000:
        return
    if isinstance(tree, str):
        s = tree.strip()
        if len(s) >= 2 and any(c.isalpha() for c in s) and not re.fullmatch(r"[0-9a-fA-F\-]{16,}", s):
            out.append(s)
    elif isinstance(tree, dict):
        for v in tree.values():
            _strings(v, out, depth + 1)
    elif isinstance(tree, (list, tuple)):
        for v in tree:
            _strings(v, out, depth + 1)


def _asset_index(env):
    return {id(a): i for i, a in enumerate(getattr(env, "assets", []) or [])}


def _has_il2cpp(src_dir):
    lib = os.path.join(src_dir, "lib")
    for _dp, _d, fs in os.walk(lib):
        if "libil2cpp.so" in fs:
            return True
    return False


def scan(proj_dir, cb=None):
    src_dir = os.path.join(proj_dir, "src")
    files, plain, unknown = [], [], []
    for p in _walk(src_dir):
        rel = _rel(src_dir, p)
        low = rel.lower()
        kind = _unity_kind(p)
        if kind == "ismeretlen":
            unknown.append(rel)
            continue
        if kind is None:
            if low.endswith(PLAIN_EXT) and (low.startswith("assets/") or "/" not in low):
                try:
                    with open(p, "rb") as f:
                        head = f.read(4000)
                    if _looks_text(head):
                        plain.append({"file": rel, "size": os.path.getsize(p),
                                      "sample": _decode(head)[:80].strip()})
                except OSError:
                    pass
            continue
        _log(cb, "Vizsgálat: " + rel)
        item = {"file": rel, "kind": kind, "types": {}, "texts": [], "no_typetree": 0, "error": ""}
        try:
            env = UnityPy.load(p)
            idx = _asset_index(env)
            for obj in env.objects:
                t = obj.type.name
                item["types"][t] = item["types"].get(t, 0) + 1
                if len(item["texts"]) >= 400:
                    continue
                ref = "a%dp%d" % (idx.get(id(obj.assets_file), 0), obj.path_id)
                try:
                    if t == "TextAsset":
                        d = obj.read()
                        raw = bytes(d.script)
                        texty = _looks_text(raw[:2000])
                        item["texts"].append({"id": ref, "type": "TextAsset" if texty else "TextAsset (bináris)",
                                              "name": d.name, "n": len(raw),
                                              "sample": _decode(raw[:200])[:80].strip() if texty else ""})
                    elif t == "MonoBehaviour":
                        tree = obj.read_typetree()
                        st = []
                        _strings(tree, st)
                        if st:
                            item["texts"].append({"id": ref, "type": "MonoBehaviour",
                                                  "name": str(tree.get("m_Name", "")), "n": len(st),
                                                  "sample": st[0][:80]})
                except Exception:
                    if t == "MonoBehaviour":
                        item["no_typetree"] += 1
        except Exception as e:
            item["error"] = "%s: %s" % (type(e).__name__, e)
        files.append(item)
    res = {"files": files, "plain": plain, "unknown": unknown,
           "il2cpp": _has_il2cpp(src_dir), "decoder": HAVE_DECODER}
    with open(os.path.join(proj_dir, "scan.json"), "w", encoding="utf-8") as f:
        json.dump(res, f, ensure_ascii=False)
    _log(cb, "Scanner kész: %d Unity fájl, %d sima szövegfájl" % (len(files), len(plain)))
    return json.dumps(res, ensure_ascii=False)


# ----------------------------------------------------------------- export
def _json_default(o):
    if isinstance(o, (bytes, bytearray, memoryview)):
        return {"__b64__": base64.b64encode(bytes(o)).decode("ascii")}
    return str(o)


def _json_hook(d):
    if len(d) == 1 and "__b64__" in d:
        return base64.b64decode(d["__b64__"])
    return d


def _decode_texture(d):
    """PIL képet ad. Ha nincs texture2ddecoder, a DXT-t a Pillow oldja meg."""
    try:
        return d.image
    except Exception as first:
        fmt = getattr(d.m_TextureFormat, "name", str(d.m_TextureFormat))
        table = {"DXT1": 1, "DXT3": 2, "DXT5": 3, "BC4": 4, "BC5": 5, "BC7": 7}
        if fmt in table:
            img = Image.frombytes("RGBA", (d.m_Width, d.m_Height), bytes(d.image_data), "bcn", table[fmt])
            return img.transpose(Image.FLIP_TOP_BOTTOM)
        raise RuntimeError("%s formátum nem dekódolható (%s)" % (fmt, first))


def _audio_ext(data):
    if data[:4] == b"RIFF":
        return ".wav"
    if data[:4] == b"OggS":
        return ".ogg"
    if data[:4] == b"FSB5":
        return ".fsb"
    if data[:3] == b"ID3" or data[:2] == b"\xff\xfb":
        return ".mp3"
    return ".bin"


def export(proj_dir, opts_json, cb=None):
    opts = json.loads(opts_json)
    src_dir = os.path.join(proj_dir, "src")
    exp = os.path.join(proj_dir, "export")
    shutil.rmtree(exp, ignore_errors=True)
    os.makedirs(exp, exist_ok=True)
    index = {}
    stat = {"text": 0, "mono": 0, "image": 0, "audio": 0, "plain": 0, "skipped": 0}

    def put(kind, rel, ident, name, ext, data, meta):
        folder = os.path.join(exp, kind, rel.replace("/", "__"))
        path = os.path.join(folder, "%s__%s%s" % (ident, _safe(name), ext))
        _write(path, data)
        index[_rel(exp, path)] = dict(meta, src=rel, id=ident, type=kind, sha=_sha(path))

    for p in _walk(src_dir):
        rel = _rel(src_dir, p)
        kind = _unity_kind(p)
        if kind in (None, "ismeretlen"):
            low = rel.lower()
            if opts.get("plain") and low.endswith(PLAIN_EXT) and (low.startswith("assets/") or "/" not in low):
                try:
                    with open(p, "rb") as f:
                        if not _looks_text(f.read(4000)):
                            continue
                    dst = os.path.join(exp, "plain", rel)
                    os.makedirs(os.path.dirname(dst), exist_ok=True)
                    shutil.copyfile(p, dst)
                    index[_rel(exp, dst)] = {"src": rel, "id": "plain", "type": "plain", "sha": _sha(dst)}
                    stat["plain"] += 1
                except OSError:
                    pass
            continue
        _log(cb, "Export: " + rel)
        try:
            env = UnityPy.load(p)
        except Exception as e:
            _log(cb, "  hiba: %s" % e)
            continue
        idx = _asset_index(env)
        for obj in env.objects:
            t = obj.type.name
            ident = "a%dp%d" % (idx.get(id(obj.assets_file), 0), obj.path_id)
            try:
                if t == "TextAsset" and opts.get("text"):
                    d = obj.read()
                    put("texts", rel, ident, d.name, ".txt", bytes(d.script), {})
                    stat["text"] += 1
                elif t == "MonoBehaviour" and opts.get("mono"):
                    tree = obj.read_typetree()
                    st = []
                    _strings(tree, st)
                    if st:
                        data = json.dumps(tree, ensure_ascii=False, indent=1, default=_json_default).encode("utf-8")
                        put("mono", rel, ident, tree.get("m_Name", ""), ".json", data, {})
                        stat["mono"] += 1
                elif t == "Texture2D" and opts.get("image"):
                    d = obj.read()
                    img = _decode_texture(d)
                    if img.width == 0:
                        continue
                    buf = io.BytesIO()
                    img.save(buf, "PNG")
                    put("images", rel, ident, d.name, ".png", buf.getvalue(), {})
                    stat["image"] += 1
                elif t == "AudioClip" and opts.get("audio"):
                    d = obj.read()
                    done = False
                    try:
                        for nm, wav in d.samples.items():
                            put("audio", rel, ident, nm or d.name, ".wav", bytes(wav), {})
                            done = True
                    except Exception:
                        pass
                    if not done:
                        raw = bytes(d.m_AudioData)
                        put("audio", rel, ident, d.name, _audio_ext(raw), raw, {"raw": True})
                    stat["audio"] += 1
            except Exception as e:
                stat["skipped"] += 1
                if stat["skipped"] <= 15:
                    _log(cb, "  kihagyva %s %s: %s" % (t, ident, e))
    with open(os.path.join(exp, "index.json"), "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False)
    _log(cb, "Export kész: %s" % stat)
    return json.dumps(stat)


# ----------------------------------------------------------------- import
def _parse_ref(ident):
    m = re.fullmatch(r"a(\d+)p(-?\d+)", ident)
    return int(m.group(1)), int(m.group(2))


def _save_env(env):
    f = getattr(env, "file", None) or list(env.files.values())[0]
    try:
        return f.save(packer="original")
    except TypeError:
        return f.save()


def import_changes(proj_dir, cb=None):
    src_dir = os.path.join(proj_dir, "src")
    exp = os.path.join(proj_dir, "export")
    mods = os.path.join(proj_dir, "mods")
    with open(os.path.join(exp, "index.json"), encoding="utf-8") as f:
        index = json.load(f)
    shutil.rmtree(mods, ignore_errors=True)
    groups = {}
    for key, item in index.items():
        p = os.path.join(exp, key)
        if os.path.exists(p) and _sha(p) != item["sha"]:
            groups.setdefault(item["src"], []).append((key, item))
    stat = {"changed_files": 0, "changed_items": 0, "errors": 0}
    if not groups:
        _log(cb, "Nincs módosított fájl az export mappában.")
        return json.dumps(stat)
    for rel, items in groups.items():
        _log(cb, "Import: %s (%d elem)" % (rel, len(items)))
        if items[0][1]["type"] == "plain":
            dst = os.path.join(mods, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copyfile(os.path.join(exp, items[0][0]), dst)
            stat["changed_files"] += 1
            stat["changed_items"] += 1
            continue
        try:
            env = UnityPy.load(os.path.join(src_dir, rel))
            idx = _asset_index(env)
            lookup = {(idx.get(id(o.assets_file), 0), o.path_id): o for o in env.objects}
            applied = 0
            for key, item in items:
                try:
                    obj = lookup[_parse_ref(item["id"])]
                    path = os.path.join(exp, key)
                    kind = item["type"]
                    if kind == "texts":
                        d = obj.read()
                        with open(path, "rb") as f:
                            d.script = f.read()
                        d.save()
                    elif kind == "mono":
                        with open(path, encoding="utf-8") as f:
                            tree = json.load(f, object_hook=_json_hook)
                        obj.save_typetree(tree)
                    elif kind == "images":
                        d = obj.read()
                        img = Image.open(path).convert("RGBA")
                        if not HAVE_ENCODER:
                            from UnityPy.enums import TextureFormat
                            d.m_TextureFormat = TextureFormat.RGBA32
                        d.image = img
                        d.m_Width, d.m_Height = img.size
                        d.save()
                    else:
                        _log(cb, "  %s importja még nincs kész: %s" % (kind, key))
                        continue
                    applied += 1
                except Exception as e:
                    stat["errors"] += 1
                    _log(cb, "  hiba (%s): %s" % (key, e))
            if applied:
                _write(os.path.join(mods, rel), _save_env(env))
                stat["changed_files"] += 1
                stat["changed_items"] += applied
        except Exception as e:
            stat["errors"] += 1
            _log(cb, "  fájl hiba (%s): %s\n%s" % (rel, e, traceback.format_exc(limit=2)))
    _log(cb, "Import kész: %s" % stat)
    return json.dumps(stat)


# ----------------------------------------------------------------- csomag építés
def _pad_extra(offset, name_len, align):
    """Extra mező, amitől az adat kezdete align-ra igazodik (zipalign)."""
    pad = (-(offset + 30 + name_len)) % align
    if pad == 0:
        return b""
    if pad < 4:
        pad += align
    return struct.pack("<HH", 0xD935, pad - 4) + b"\x00" * (pad - 4)


def build(proj_dir, out_name, cb=None):
    orig = [f for f in os.listdir(proj_dir) if f.startswith("original.")]
    if not orig:
        raise RuntimeError("Nincs eredeti csomag a projektben")
    orig = os.path.join(proj_dir, orig[0])
    mods = os.path.join(proj_dir, "mods")
    is_apk = orig.lower().endswith(".apk")
    ext = os.path.splitext(orig)[1]
    out_dir = os.path.join(proj_dir, "build")
    os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, "%s_unsigned%s" % (out_name, ext))
    replaced = 0
    with zipfile.ZipFile(orig) as zin, zipfile.ZipFile(out, "w") as zout:
        infos = zin.infolist()
        for i, zi in enumerate(infos):
            name = zi.filename
            if is_apk and re.match(r"META-INF/.*(\.(SF|RSA|DSA|EC|KFC)|MANIFEST\.MF)$", name):
                continue  # újraaláírjuk
            mod = os.path.join(mods, name)
            if not zi.is_dir() and os.path.isfile(mod):
                with open(mod, "rb") as f:
                    data = f.read()
                replaced += 1
            else:
                data = zin.read(zi)
            ni = zipfile.ZipInfo(name, date_time=zi.date_time)
            ni.compress_type = zi.compress_type
            ni.external_attr = zi.external_attr
            if zi.compress_type == zipfile.ZIP_STORED and not zi.is_dir():
                align = 4096 if name.endswith(".so") else 4
                ni.extra = _pad_extra(zout.fp.tell(), len(name.encode("utf-8")), align)
            zout.writestr(ni, data)
            if i % 300 == 0:
                _log(cb, "Csomagolás: %d / %d" % (i, len(infos)))
    _log(cb, "Csomag kész (%d módosított fájl)" % replaced)
    return json.dumps({"path": out, "replaced": replaced, "apk": is_apk})
