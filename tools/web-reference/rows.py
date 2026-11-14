# Compares vertical layout: lists the blocks of "ink" rows (in dp) in the web and native shots.
import sys, os
from PIL import Image
ref=os.path.dirname(os.path.abspath(__file__))
web=os.environ.get('WEB', ref+'/web'); nat=os.path.join(ref,'..','..','app','build','screens')
def blocks(path):
    im=Image.open(path).convert('RGB'); w,h=im.size; px=im.load()
    bg=px[4, h//2]
    out=[]; start=None
    for y in range(h):
        ink=sum(1 for x in range(8, w-8, 3) if max(abs(px[x,y][i]-bg[i]) for i in range(3))>40)
        on=ink>3
        if on and start is None: start=y
        if not on and start is not None: out.append((start/2, y/2)); start=None
    if start is not None: out.append((start/2,h/2))
    # merge blocks separated by < 3dp
    merged=[]
    for b in out:
        if merged and b[0]-merged[-1][1] < 3: merged[-1]=(merged[-1][0], b[1])
        else: merged.append(b)
    return merged
for name in sys.argv[1:]:
    a=blocks(f'{web}/{name}.png'); b=blocks(f'{nat}/{name}.png')
    print('==',name, 'web', len(a), 'native', len(b))
    for i in range(max(len(a),len(b))):
        A=a[i] if i<len(a) else None; B=b[i] if i<len(b) else None
        d='' if not(A and B) else f'  Δtop {B[0]-A[0]:+.1f} Δh {(B[1]-B[0])-(A[1]-A[0]):+.1f}'
        print(f'  {str(A):>16} | {str(B):>16}{d}')
