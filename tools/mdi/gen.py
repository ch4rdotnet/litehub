#!/usr/bin/env python3
# turns the icons listed in icons.txt into ui/components/src/main/assets/icons/mdi.txt.
# paths come out absolute and only M, L, C, Z, so the parser on device stays tiny.
# usage: gen.py <path to @mdi/js mdi.js>
import math
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE.parent.parent / "ui/components/src/main/assets/icons/mdi.txt"
TOKEN = re.compile(r"[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?")


def camel(name):
    return "mdi" + "".join(p[:1].upper() + p[1:] for p in name.split("-"))


def arc_to_cubics(x1, y1, rx, ry, phi, large, sweep, x2, y2):
    # endpoint to centre parameterisation, svg spec appendix b.2.4
    if rx == 0 or ry == 0:
        return [(x1, y1, x2, y2, x2, y2)]
    rx, ry = abs(rx), abs(ry)
    cp, sp = math.cos(math.radians(phi)), math.sin(math.radians(phi))
    dx, dy = (x1 - x2) / 2, (y1 - y2) / 2
    x1p, y1p = cp * dx + sp * dy, -sp * dx + cp * dy
    lam = (x1p ** 2) / (rx ** 2) + (y1p ** 2) / (ry ** 2)
    if lam > 1:
        rx, ry = rx * math.sqrt(lam), ry * math.sqrt(lam)
    num = rx ** 2 * ry ** 2 - rx ** 2 * y1p ** 2 - ry ** 2 * x1p ** 2
    den = rx ** 2 * y1p ** 2 + ry ** 2 * x1p ** 2
    coef = math.sqrt(max(0.0, num / den)) if den else 0.0
    if large == sweep:
        coef = -coef
    cxp, cyp = coef * rx * y1p / ry, -coef * ry * x1p / rx
    cx = cp * cxp - sp * cyp + (x1 + x2) / 2
    cy = sp * cxp + cp * cyp + (y1 + y2) / 2

    def angle(ux, uy, vx, vy):
        a = math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        return a

    t1 = angle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dt = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not sweep and dt > 0:
        dt -= 2 * math.pi
    elif sweep and dt < 0:
        dt += 2 * math.pi
    segs = max(1, math.ceil(abs(dt) / (math.pi / 2) - 1e-9))
    step = dt / segs
    k = 4 / 3 * math.tan(step / 4)
    out = []
    for i in range(segs):
        a1, a2 = t1 + i * step, t1 + (i + 1) * step
        c1, s1, c2, s2 = math.cos(a1), math.sin(a1), math.cos(a2), math.sin(a2)
        p = [
            (c1 - k * s1, s1 + k * c1),
            (c2 + k * s2, s2 - k * c2),
            (c2, s2),
        ]
        pts = []
        for ux, uy in p:
            ux, uy = ux * rx, uy * ry
            pts += [cp * ux - sp * uy + cx, sp * ux + cp * uy + cy]
        out.append(tuple(pts))
    # land exactly on the endpoint, rounding drift shows up as hairline gaps
    last = list(out[-1])
    last[4], last[5] = x2, y2
    out[-1] = tuple(last)
    return out


def normalise(d):
    toks = TOKEN.findall(d)
    i = 0
    out = []
    cx = cy = sx = sy = 0.0
    last_c = None  # reflected control point for s
    last_q = None  # control point for t
    cmd = None

    def num():
        nonlocal i
        v = float(toks[i])
        i += 1
        return v

    def flag():
        # flags can be packed like "01", the tokenizer may hand them over joined
        nonlocal i
        t = toks[i]
        if len(t) > 1 and t[0] in "01" and not t.startswith(("0.", "1.")):
            toks[i] = t[1:]
            return int(t[0])
        i += 1
        return int(float(t))

    while i < len(toks):
        if toks[i].isalpha():
            cmd = toks[i]
            i += 1
        elif cmd is None:
            raise ValueError("path starts with a number")
        rel = cmd.islower()
        c = cmd.upper()
        ox, oy = (cx, cy) if rel else (0.0, 0.0)
        if c == "Z":
            out.append("Z")
            cx, cy = sx, sy
            last_c = last_q = None
            continue
        if c == "M":
            cx, cy = num() + ox, num() + oy
            sx, sy = cx, cy
            out.append(f"M {fmt(cx)} {fmt(cy)}")
            cmd = "l" if rel else "L"
            last_c = last_q = None
        elif c == "L":
            cx, cy = num() + ox, num() + oy
            out.append(f"L {fmt(cx)} {fmt(cy)}")
            last_c = last_q = None
        elif c == "H":
            cx = num() + (cx if rel else 0.0)
            out.append(f"L {fmt(cx)} {fmt(cy)}")
            last_c = last_q = None
        elif c == "V":
            cy = num() + (cy if rel else 0.0)
            out.append(f"L {fmt(cx)} {fmt(cy)}")
            last_c = last_q = None
        elif c in "CS":
            if c == "C":
                x1, y1 = num() + ox, num() + oy
            else:
                x1, y1 = (2 * cx - last_c[0], 2 * cy - last_c[1]) if last_c else (cx, cy)
            x2, y2 = num() + ox, num() + oy
            x, y = num() + ox, num() + oy
            out.append("C " + " ".join(fmt(v) for v in (x1, y1, x2, y2, x, y)))
            last_c, last_q = (x2, y2), None
            cx, cy = x, y
        elif c in "QT":
            if c == "Q":
                qx, qy = num() + ox, num() + oy
            else:
                qx, qy = (2 * cx - last_q[0], 2 * cy - last_q[1]) if last_q else (cx, cy)
            x, y = num() + ox, num() + oy
            x1, y1 = cx + 2 / 3 * (qx - cx), cy + 2 / 3 * (qy - cy)
            x2, y2 = x + 2 / 3 * (qx - x), y + 2 / 3 * (qy - y)
            out.append("C " + " ".join(fmt(v) for v in (x1, y1, x2, y2, x, y)))
            last_q, last_c = (qx, qy), None
            cx, cy = x, y
        elif c == "A":
            rx, ry, phi = num(), num(), num()
            large, sweep = flag(), flag()
            x, y = num() + ox, num() + oy
            for seg in arc_to_cubics(cx, cy, rx, ry, phi, large, sweep, x, y):
                out.append("C " + " ".join(fmt(v) for v in seg))
            cx, cy = x, y
            last_c = last_q = None
        else:
            raise ValueError(f"unknown command {cmd}")
    return " ".join(out)


def fmt(v):
    s = f"{v:.3f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def main():
    source = Path(sys.argv[1]).read_text()
    paths = dict(re.findall(r'export var (mdi\w+) = "([^"]+)"', source))
    names = [l.strip() for l in (HERE / "icons.txt").read_text().splitlines() if l.strip() and not l.startswith("#")]
    missing = [n for n in names if camel(n) not in paths]
    if missing:
        sys.exit("not in mdi: " + ", ".join(missing))
    lines = [f"{n}\t{normalise(paths[camel(n)])}" for n in sorted(set(names))]
    OUT.write_text("\n".join(lines) + "\n")
    print(f"{len(lines)} icons, {OUT.stat().st_size} bytes")


if __name__ == "__main__":
    main()
