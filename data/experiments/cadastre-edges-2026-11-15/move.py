"""Drags that bring map point (TE, TN) to the screen point (900, 380), from the current centre (E, N) there.
python move.py E N TE TN [SX SY] -> lines 'x0,y0 x1,y1'"""
import sys
E, N, TE, TN = map(float, sys.argv[1:5])
SX, SY = (float(sys.argv[5]), float(sys.argv[6])) if len(sys.argv) > 6 else (28.241, 28.261)
dx, dy = round((TE - E) / SX), round((TN - N) / SY)   # drag left by dx (east), down by dy (north)
while abs(dx) > 3 or abs(dy) > 3:
    sx, sy = max(-850, min(850, dx)), max(-380, min(380, dy))
    x0 = 1380 if sx > 0 else 480
    y0 = 210 if sy > 0 else 600
    print(f'{x0},{y0} {x0 - sx},{y0 + sy}')
    dx -= sx; dy -= sy
