"""WCAG 2.1 contrast ratios for the app's configured text colour pairs.

Reads the colours from Theme.kt, so the result follows the code. Pairs checked:
- every Material "onX" colour on its "X" colour, in the light and dark schemes;
- text colours the screens draw directly on a surface (error, onSurfaceVariant and primary on the
  background and on the white printed-label surface);
- every severity sticker's text on its sticker colour.
It does not inspect rendered screens, so it cannot cover colours set elsewhere or images.

Usage: python scripts/contrast_check.py > docs/evidence/contrast-YYYYMMDD-HHMMSS.txt
Exit status 1 if any pair is below 4.5:1 (WCAG 2.1 SC 1.4.3, normal text).
"""
import re
import sys
from pathlib import Path

THEME = Path(__file__).resolve().parent.parent / 'app/src/main/kotlin/com/airgaprx/app/ui/theme/Theme.kt'
NAMED = {'Color.White': 'FFFFFF', 'Color.Black': '000000'}


def colour(expr, consts):
    expr = expr.strip()
    if expr in NAMED:
        return NAMED[expr]
    m = re.fullmatch(r'Color\(0x[0-9A-Fa-f]{2}([0-9A-Fa-f]{6})\)', expr)
    if m:
        return m.group(1).upper()
    return consts[expr]


def luminance(hex6):
    def channel(c):
        c = c / 255
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = (int(hex6[i:i + 2], 16) for i in (0, 2, 4))
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)


def ratio(fg, bg):
    a, b = sorted((luminance(fg), luminance(bg)), reverse=True)
    return (a + 0.05) / (b + 0.05)


def scheme(src, name, consts):
    body = re.search(name + r' = \w+ColorScheme\((.*?)\n\)', src, re.S).group(1)
    return {k: colour(v, consts) for k, v in re.findall(r'(\w+) = (Color(?:\(0x\w+\)|\.\w+)|[A-Z]\w+)', body)}


def main():
    src = THEME.read_text(encoding='utf-8')
    consts = {k: colour(v, {}) for k, v in re.findall(r'private val (\w+) = (Color\(0x\w+\))', src)}
    pairs = []
    for name in ('Light', 'Dark'):
        s = scheme(src, name, consts)
        for role, bg in s.items():
            fg = s.get('on' + role[0].upper() + role[1:])
            if fg:
                pairs.append((f'{name}: on{role[0].upper() + role[1:]} on {role}', fg, bg))
        for text in ('error', 'onSurfaceVariant', 'primary'):
            for surf in ('surface', 'surfaceContainerLowest'):
                if text in s and surf in s:
                    pairs.append((f'{name}: {text} on {surf}', s[text], s[surf]))
    sticker = dict(re.findall(r'val (\w+) = (Color(?:\(0x\w+\)|\.\w+))', src.split('object SeverityColors')[1].split('}')[0]))
    for level in ('major', 'moderate', 'minor', 'unknown'):
        on = 'on' + level[0].upper() + level[1:]
        pairs.append((f'Sticker: {on} on {level}', colour(sticker[on], consts), colour(sticker[level], consts)))

    print(f'# WCAG 2.1 contrast of configured text colour pairs, from {THEME.relative_to(Path(__file__).resolve().parent.parent).as_posix()}')
    print('# pair\tforeground\tbackground\tratio\tAA normal text (4.5:1)')
    worst = None
    for label, fg, bg in pairs:
        r = ratio(fg, bg)
        worst = min(worst or (r, label), (r, label))
        print(f'{label}\t#{fg}\t#{bg}\t{r:.2f}:1\t{"pass" if r >= 4.5 else "FAIL"}')
    print(f'# {len(pairs)} pairs; lowest {worst[0]:.2f}:1 ({worst[1]})')
    return 0 if worst[0] >= 4.5 else 1


if __name__ == '__main__':
    sys.exit(main())
