#!/usr/bin/env python3
"""Generate printable horizontal shelf labels using exact normalized ScanSklad addresses.
Dependencies for regeneration: pip install qrcode[pil]. The HTML in this project is already generated.
"""
import base64, csv, html, io, pathlib
import qrcode
from qrcode.constants import ERROR_CORRECT_M
ROOT=pathlib.Path(__file__).resolve().parents[1]
REF=ROOT/'assets'/'address_catalog.csv'
OUT=ROOT/'Этикетки_стеллажей_горизонтальные.html'
SAMPLE=ROOT/'QR_образец_142-П1-1.png'
with REF.open(encoding='utf-8-sig', newline='') as f:
    addresses=list(csv.DictReader(f))
assert addresses and 'Единый адрес для ScanSklad' in addresses[0]
head='''<!DOCTYPE html><html lang="ru"><head><meta charset="utf-8"><title>QR-этикетки ScanSklad</title>
<style>@page {size:A4; margin:12mm} body{margin:0;color:#111;background:white;font-family:Arial,Verdana,sans-serif}
.info{padding:6mm 0;font-size:12pt} .label{box-sizing:border-box;width:180mm;height:31mm;display:flex;align-items:center;justify-content:space-between;border:1px solid #666;padding:2mm 4mm;page-break-inside:avoid;break-inside:avoid;margin:0 0 2mm 0}
.name{font-family:Verdana,Arial,sans-serif;font-weight:700;font-size:27pt;white-space:nowrap} .qr{width:27mm;height:27mm;object-fit:contain} @media print{.info{display:none}} </style></head><body>
<div class="info">Горизонтальные этикетки ScanSklad. При печати выбрать масштаб 100%, не включать «Подогнать под страницу». QR содержит только текст адреса, без URL.</div>
'''
rows=[]
for i,row in enumerate(addresses):
    code=row['Единый адрес для ScanSklad'].strip()
    q=qrcode.QRCode(version=None,error_correction=ERROR_CORRECT_M, box_size=12,border=4)
    q.add_data(code.encode('utf-8'));q.make(fit=True)
    im=q.make_image(fill_color='black', back_color='white').convert('RGB')
    if i==0 or code=='142/П1/1':
        if code=='142/П1/1': im.save(SAMPLE)
    buf=io.BytesIO();im.save(buf,format='PNG')
    encoded=base64.b64encode(buf.getvalue()).decode()
    rows.append('<div class="label"><span class="name">'+html.escape(code)+'</span><img class="qr" alt="QR '+html.escape(code)+'" src="data:image/png;base64,'+encoded+'"></div>')
OUT.write_text(head+'\n'.join(rows)+'</body></html>',encoding='utf-8')
print(f'{len(rows)} labels, {OUT.stat().st_size} bytes; sample {SAMPLE.stat().st_size}')
