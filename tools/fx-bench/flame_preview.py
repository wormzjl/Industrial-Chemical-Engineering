# A numpy port of fx_flame.fsh (and the ribbon geometry of fx_flame.vsh / FlameModel.half) for a straight flame seen side on.
import sys, numpy as np
from PIL import Image
f32 = np.float32
def fract(a): return a - np.floor(a)
def hash2(x, y):
    x = fract(x * f32(123.34)); y = fract(y * f32(456.21))
    d = x * (x + f32(45.32)) + y * (y + f32(45.32))
    x = x + d; y = y + d
    return fract(x * y)
def noise(x, y):
    ix, iy = np.floor(x), np.floor(y); fx, fy = x - ix, y - iy
    ux, uy = fx*fx*(3-2*fx), fy*fy*(3-2*fy)
    a = hash2(ix, iy); b = hash2(ix+1, iy); c = hash2(ix, iy+1); d = hash2(ix+1, iy+1)
    return (a + (b-a)*ux) + ((c + (d-c)*ux) - (a + (b-a)*ux))*uy
def flow(x, y, t):
    s = 0; amp = .5; drift = .3
    for i in range(4):
        s = s + amp*noise(x - drift*t, y - t)
        x = x*2.03 + 1.7; y = y*2.03 + 9.2; t *= 2.33; drift = -drift; amp *= .5
    return s
def smooth(a, b, v):
    k = np.clip((v-a)/(b-a), 0, 1); return k*k*(3-2*k)
def ramp(h):
    def mix(c0, c1, k): return c0 + (c1-c0)*k[...,None]
    c = mix(np.array([.8,.2,.02]), np.array([1,.4,.05]), smooth(0,.25,h))
    c = mix(c, np.array([1,.6,.12]), smooth(.2,.5,h))
    c = mix(c, np.array([1,.85,.45]), smooth(.45,.8,h))
    return mix(c, np.array([1,.97,.85]), smooth(.8,1,h))
EDDY, ROOT, ROOM, L, HALF = .22, .2, 1.5, 30., .09*30
def half(v): return np.where(v < .7, np.sin(np.pi/2*v/.7), 1-.5*(v-.7))*HALF
def ribbon(t, seed, scale, heat, soot, W=200, H=400):
    # Pixel grid in metres: x across -6..6, y up 0..32.
    X, Y = np.meshgrid(np.linspace(-6, 6, W), np.linspace(32, 0, H))
    along = np.clip(Y/L, 0, 1); hw = np.maximum(half(along)*scale, .15*(1-along))
    xq = X/(hw*ROOM)                       # corner coordinate -1..1 over the drawn quad
    inside = (np.abs(xq) <= 1) & (Y <= L)
    m = Y
    ex = X/(EDDY*(m+ROOT)); ey = np.log(1+m/ROOT)/EDDY
    x = xq*ROOM
    px = ex*2.2 + seed*17; py = ey*1.1 + seed*31
    wx = noise(px*.5, py*.5 + t*1.3) - .5; wy = noise(px*.5+4.3, py*.5 + t*1.1) - .5
    fl = flow(px + 1.2*wx, py + 1.2*wy, t*3.3)
    n = (fl - .47)/.11
    reach = .84 + .2*(noise(np.array(t*1.3), np.array(seed*50.)) - .5) + .1*(noise(np.array(t*3.7), np.array(seed*20.)) - .5)
    puff = 1 + .22*smooth(.1,.6,along)*np.sin(ey*1.4 - t*4 + seed*9)
    I = 1 - np.abs(x)/puff + (.12+.4*along)*n - 1.1*smooth(.5, reach+.08, along) - smooth(1.2, ROOM, np.abs(x))
    a = smooth(0, .07, I)*.85*inside
    h = np.clip(I*(.45+.55*heat) - .15*along, 0, 1)
    rgb = ramp(h); a = a*(.6+.4*smooth(0,.3,h))
    rgb *= (1 - .5*soot*smooth(.6,1,along)*(1-h))[...,None]
    rgb *= ((.9+.5*h)*(.9+.2*noise(np.array(t*7.), np.array(seed*61.))))[...,None]
    return np.clip(rgb,0,1), a, fl[inside]
def frame(t, seed0=.31, soot=.3):
    sky = np.zeros((400,200,3)) + np.array([.55,.7,.95])
    out = sky
    for j, (scale, heat) in enumerate([(1,.6), (.82,.8), (.64,1.)]):
        rgb, a, fl = ribbon(t, ((seed0*4096 + j*397) % 4096)/4096, scale, heat, soot)
        out = out*(1-a[...,None]) + rgb*a[...,None]
    return (out*255).astype(np.uint8), fl
if __name__ == '__main__':
    _, fl = frame(0.)
    print('flow mean %.3f std %.3f' % (fl.mean(), fl.std()))
    frames = [Image.fromarray(frame(i/10)[0]) for i in range(40)]
    frames[0].save(sys.argv[1] + '/flame.gif', save_all=True, append_images=frames[1:], duration=100, loop=0)
    sheet = Image.new('RGB', (200*8, 400))
    for k in range(8): sheet.paste(frames[k*5], (200*k, 0))
    sheet.save(sys.argv[1] + '/flame-sheet.png')
