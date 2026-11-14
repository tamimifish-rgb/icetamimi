# side-by-side: web (left) | native (right)
import sys, os
from PIL import Image
ref=os.path.dirname(os.path.abspath(__file__))
web=os.environ.get('WEB', ref+'/web'); nat=os.path.join(ref,'..','..','app','build','screens')
scale=float(os.environ.get('SCALE','1'))
for name in sys.argv[1:]:
    a=Image.open(f'{web}/{name}.png').convert('RGB'); b=Image.open(f'{nat}/{name}.png').convert('RGB')
    if b.size!=a.size: b=b.resize((a.width, int(b.height*a.width/b.width)), Image.LANCZOS)
    H=max(a.height,b.height); sheet=Image.new('RGB',(a.width+b.width+16,H),(255,0,255)); sheet.paste(a,(0,0)); sheet.paste(b,(a.width+16,0))
    if scale!=1: sheet=sheet.resize((int(sheet.width*scale), int(sheet.height*scale)), Image.LANCZOS)
    sheet.save(f'{ref}/pair-{name}.png')
