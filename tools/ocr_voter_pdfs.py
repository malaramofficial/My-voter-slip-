#!/usr/bin/env python3
"""Batch OCR electoral-roll PDFs into per-voter records and print-ready slips."""
from __future__ import annotations
import csv, html, json, re, sys, unicodedata
from collections import defaultdict
from pathlib import Path
import fitz
import pytesseract
from PIL import Image
from pytesseract import Output

ROOT = Path(__file__).resolve().parents[1]
PDF_DIR = ROOT / "voter-pdfs"
OUT = ROOT / "ocr-output"
OUT.mkdir(exist_ok=True)
DPI_SCALE = 2.2
GRID_COLS, GRID_ROWS = 3, 10
LEFT, RIGHT, TOP, BOTTOM = 0.012, 0.988, 0.105, 0.982
EPIC_RE = re.compile(r"\b[A-Z]{2,4}\s*[-/]?\s*\d{5,8}\b", re.I)
FIELD_PATTERNS = {
    "relative_name": re.compile(r"(?:पिता\s*का\s*नाम|पति\s*का\s*नाम|माता\s*का\s*नाम|अन्य\s*का\s*नाम|father'?s?\s*name|husband'?s?\s*name|mother'?s?\s*name|relative'?s?\s*name)\s*[:：\-]?\s*(.+)", re.I),
    "name": re.compile(r"^\s*(?:निर्वाचक\s*का\s*नाम|मतदाता\s*का\s*नाम|name|नाम|प्र)\s*[:：\-]?\s*(.+)", re.I),
    "house_no": re.compile(r"(?:मकान\s*(?:संख्या|नं?\.?)|घर\s*संख्या|house\s*(?:no|number))\s*[:：\-]?\s*(.+)", re.I),
    "age": re.compile(r"(?:आयु|उम्र|age)\s*[:：\-]?\s*([०-९0-9]{1,3})", re.I),
    "gender": re.compile(r"(?:लिंग|gender)\s*[:：\-]?\s*(महिला|महिल|स्त्री|पुरुष|पुरूष|पुरू|अन्य|female|male|other)", re.I),
    "serial_no": re.compile(r"(?:क्रम\s*संख्या|serial\s*(?:no|number)?|क्र\.?\s*सं\.?)\s*[:：\-]?\s*([०-९0-9]{1,5})", re.I),
}
DEVANAGARI_DIGITS = str.maketrans("०१२३४५६७८९", "0123456789")

def norm(value: str) -> str:
    value = unicodedata.normalize("NFC", value or "").lower()
    return re.sub(r"[^\w]", "", value, flags=re.UNICODE)

def parse_fields(text: str) -> dict:
    fields = {k: "" for k in ("serial_no", "epic", "name", "relation_type", "relative_name", "house_no", "age", "gender")}
    lines = [re.sub(r"\s+", " ", x).strip() for x in text.splitlines() if x.strip()]
    for line in lines:
        for key, pattern in FIELD_PATTERNS.items():
            m = pattern.search(line)
            if m and not fields.get(key):
                fields[key] = m.group(1).strip(" :-")
        if not fields["epic"]:
            m = EPIC_RE.search(line.upper())
            if m:
                fields["epic"] = re.sub(r"\s+", "", m.group(0)).upper()
        if not fields["relation_type"]:
            low = line.lower()
            if "पिता" in line or "father" in low: fields["relation_type"] = "पिता"
            elif "पति" in line or "husband" in low: fields["relation_type"] = "पति"
            elif "माता" in line or "mother" in low: fields["relation_type"] = "माता"
    for key in ("name", "relative_name", "house_no"):
        value = fields[key]
        value = re.sub(r"\b(?:Photo\s*is|Available)\b", " ", value, flags=re.I)
        value = re.sub(r"\s+", " ", value).strip(" :-|_")
        value = re.sub(r"\s+(?:ना|पि|af|ar|at|मव)$", "", value, flags=re.I)
        fields[key] = value
    fields["age"] = fields["age"].translate(DEVANAGARI_DIGITS)
    fields["serial_no"] = fields["serial_no"].translate(DEVANAGARI_DIGITS)
    fields["house_no"] = fields["house_no"].strip()
    return fields

def ocr_card(img: Image.Image) -> tuple[str, float]:
    gray = img.convert("L")
    gray = gray.resize((max(1, gray.width * 2), max(1, gray.height * 2)))
    variants = [gray, gray.point(lambda p: 255 if p > 170 else 0)]
    best_text, best_score = "", -1.0
    for variant in variants:
        data = pytesseract.image_to_data(variant, lang="hin+eng", config="--psm 6", output_type=Output.DICT)
        grouped = {}
        confs = []
        for i, word in enumerate(data["text"]):
            word = (word or "").strip()
            if not word:
                continue
            key = (data["block_num"][i], data["par_num"][i], data["line_num"][i])
            grouped.setdefault(key, []).append(word)
            try:
                confidence = float(data["conf"][i])
                if confidence >= 0:
                    confs.append(confidence)
            except (ValueError, TypeError):
                pass
        text = "\n".join(" ".join(words) for _, words in sorted(grouped.items())).strip()
        score = (sum(confs) / len(confs) if confs else 0) + min(sum(len(v) for v in grouped.values()), 30) * 0.25
        if len(text) > len(best_text) and score >= best_score - 3:
            best_text, best_score = text, score
        elif score > best_score and len(text) >= max(12, len(best_text) * 0.7):
            best_text, best_score = text, score
    return best_text, max(0.0, best_score)

def looks_like_record(text: str, fields: dict) -> bool:
    devanagari = sum(1 for c in text if "\u0900" <= c <= "\u097f")
    return len(text) >= 16 and bool(fields["epic"]) and bool(fields["name"] or fields["relative_name"])

def main():
    pdfs = sorted(PDF_DIR.glob("*.pdf"), key=lambda p: p.name.lower())
    if not pdfs:
        print(f"No PDFs found in {PDF_DIR}", file=sys.stderr); return 2
    records = []
    for pdf_path in pdfs:
        print(f"Processing: {pdf_path.name}", flush=True)
        doc = fitz.open(pdf_path)
        for page_idx, page in enumerate(doc):
            pix = page.get_pixmap(matrix=fitz.Matrix(DPI_SCALE, DPI_SCALE), alpha=False)
            page_img = Image.frombytes("RGB", [pix.width, pix.height], pix.samples)
            x0, x1 = int(pix.width * LEFT), int(pix.width * RIGHT)
            y0, y1 = int(pix.height * TOP), int(pix.height * BOTTOM)
            cw, ch = (x1-x0)//GRID_COLS, (y1-y0)//GRID_ROWS
            for row in range(GRID_ROWS):
                for col in range(GRID_COLS):
                    left = x0 + col*cw; top = y0 + row*ch
                    right = x1 if col == GRID_COLS-1 else left+cw
                    bottom = y1 if row == GRID_ROWS-1 else top+ch
                    crop = page_img.crop((left, top, right, bottom))
                    raw, confidence = ocr_card(crop)
                    fields = parse_fields(raw)
                    if not looks_like_record(raw, fields):
                        continue
                    records.append({
                        "record_id": f"{pdf_path.stem}::p{page_idx+1:04d}::r{row+1}c{col+1}",
                        "source_pdf": pdf_path.name, "page": page_idx+1,
                        "grid_row": row+1, "grid_col": col+1,
                        **fields, "ocr_confidence": round(confidence, 2),
                        "raw_ocr": raw, "family_id": "",
                    })
        doc.close()
        print(f"  records so far: {len(records)}", flush=True)

    # Family links require a name-to-relative-name match; house number alone is not enough.
    by_name = defaultdict(list)
    for i, rec in enumerate(records):
        key = norm(rec.get("name", ""))
        if key: by_name[(rec["source_pdf"], key)].append(i)
    parent = list(range(len(records)))
    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i
    def union(a,b):
        ra, rb = find(a), find(b)
        if ra != rb: parent[rb] = ra
    for i, rec in enumerate(records):
        rel = norm(rec.get("relative_name", ""))
        if not rel: continue
        for j in by_name.get((rec["source_pdf"], rel), []):
            house_a, house_b = norm(rec.get("house_no","")), norm(records[j].get("house_no",""))
            if house_a and house_b and house_a != house_b: continue
            if i != j: union(i,j)
    components = defaultdict(list)
    for i in range(len(records)): components[find(i)].append(i)
    family_num = 0
    for members in components.values():
        if len(members) > 1:
            family_num += 1
            fid = f"F{family_num:05d}"
            for i in members: records[i]["family_id"] = fid

    (OUT / "voters.json").write_text(json.dumps({"source_pdfs":[p.name for p in pdfs], "count":len(records), "records":records}, ensure_ascii=False, indent=2), encoding="utf-8")
    columns = ["record_id","source_pdf","page","grid_row","grid_col","serial_no","epic","name","relation_type","relative_name","house_no","age","gender","family_id","ocr_confidence","raw_ocr"]
    with (OUT / "voters.csv").open("w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=columns); w.writeheader(); w.writerows(records)
    with (OUT / "family_groups.csv").open("w", newline="", encoding="utf-8-sig") as f:
        cols = ["family_id","source_pdf","name","relative_name","house_no","epic","record_id"]
        w = csv.DictWriter(f, fieldnames=cols); w.writeheader()
        for rec in records:
            if rec["family_id"]: w.writerow({k:rec.get(k,"") for k in cols})
    cards = []
    for rec in records:
        name = rec["name"] or "(नाम OCR से स्पष्ट नहीं)"
        details = [("पिता/पति/अन्य का नाम",rec["relative_name"]),("मकान नंबर",rec["house_no"]),("आयु",rec["age"]),("लिंग",rec["gender"]),("EPIC नंबर",rec["epic"]),("PDF",rec["source_pdf"]),("पृष्ठ",rec["page"])]
        rows = "".join(f"<div class='field'><b>{html.escape(k)}:</b> {html.escape(str(v))}</div>" for k,v in details if v)
        cards.append(f"<article class='card'><div class='serial'>क्रमांक: {html.escape(str(rec['serial_no'] or '—'))}</div><h2>{html.escape(name)}</h2>{rows}<div class='raw'>{html.escape(rec['raw_ocr'])}</div></article>")
    slips = """<!doctype html><html lang="hi"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>मतदाता पर्चियाँ</title>
<style>@page{size:A4;margin:8mm}*{box-sizing:border-box}body{font-family:'Noto Sans Devanagari','Nirmala UI',sans-serif;color:#182638;margin:0}.notice{padding:10px;border:1px solid #c9d6e4;margin-bottom:8px;font-size:11px}.sheet{display:grid;grid-template-columns:1fr 1fr;gap:5mm;align-items:stretch}.card{border:1px solid #596b7e;border-radius:5px;padding:8px;min-height:48mm;break-inside:avoid;position:relative}h2{font-size:15px;margin:5px 0 8px;border-bottom:1px solid #ccd5df;padding-bottom:5px}.field{font-size:10px;line-height:1.45;margin:2px 0;overflow-wrap:anywhere}.serial{font-size:9px;color:#45566a;text-align:right}.raw{font-size:8px;color:#64748b;border-top:1px dotted #cbd5e1;margin-top:5px;padding-top:4px;white-space:pre-wrap;max-height:24mm;overflow:hidden}@media screen{body{padding:12px;background:#eef2f7}.sheet{max-width:210mm;margin:auto}.card{background:white}}</style>
<body><div class="notice"><b>मतदाता पर्चियाँ — OCR द्वारा तैयार प्रारंभिक मसौदा</b><br>OCR गलत पढ़ सकता है; नाम/नंबर मूल PDF से जाँचें। केवल मकान नंबर के आधार पर परिवार नहीं बनाया गया है। Print → Save as PDF से प्रिंट करें।</div><main class="sheet">""" + "".join(cards) + "</main></body></html>"
    (OUT / "slips.html").write_text(slips, encoding="utf-8")
    summary = {"pdf_count":len(pdfs), "pdfs":[p.name for p in pdfs], "voter_records":len(records), "linked_family_groups":family_num, "output_files":["voters.json","voters.csv","family_groups.csv","slips.html"]}
    (OUT / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
