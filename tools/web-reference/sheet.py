import sys
from PIL import Image
out=sys.argv[1]; files=sys.argv[2:]
ims=[Image.open(f).convert('RGB') for f in files]
scale=float(__import__('os').environ.get('SCALE','0.75'))
ims=[im.resize((int(im.width*scale),int(im.height*scale)), Image.LANCZOS) for im in ims]
gap=12; W=sum(i.width for i in ims)+gap*(len(ims)-1); H=max(i.height for i in ims)
sheet=Image.new('RGB',(W,H),(255,0,255)); x=0
for im in ims: sheet.paste(im,(x,0)); x+=im.width+gap
sheet.save(out)
