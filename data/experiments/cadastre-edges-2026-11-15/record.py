"""Check one holder lookup and log it only if the click really hit the target.

python record.py DIST TARGET READ_E READ_N PLOT HOLDER [BIN]
  READ_E READ_N  the two numbers of the page's coordinate readout (the line like "12472037.8471, 5821509.5862"),
                 read from get_page_text right after the card loaded: they are the map point that was clicked
  PLOT           the 11-digit cadastral number at the start of the card title, or 'none' if the page said
                 "По вашему запросу объектов не найдено"
  HOLDER         the name under "Наименование" (as printed), or 'hidden' if the page said
                 "Информация о правообладателях отсутствует", or 'none' with PLOT none
  BIN            the 12 digits under "БИН" (omit for hidden / none)
Prints OK (logged), MISMATCH (the click missed: redo the target), or NOT-READY (no holder line yet: wait, read again).
A lookup counts only if the readout lies within 120 m of the target's map point."""
import csv
import math
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def clean(name):
    name = re.sub(r'\s+', ' ', name.strip())
    name = re.sub(r'(?i)товарищество с ограниченной ответственностью', 'ТОО', name)
    name = re.sub(r'^ТОО "ТОО ', 'ТОО "', name)
    name = re.sub(r'(^|\s)"\s+', r'\1"', name, count=1)    # only after an opening quote
    return name


def main():
    if len(sys.argv) < 7:
        print(__doc__)
        sys.exit(2)
    dist, target, re_, rn, plot, holder = sys.argv[1:7]
    bin_ = sys.argv[7] if len(sys.argv) > 7 else ''
    T = {t['target']: t for t in csv.DictReader(open(os.path.join(HERE, f'targets-{dist}.csv'), encoding='utf-8'))}
    if target not in T:
        print('UNKNOWN TARGET', target)
        sys.exit(1)
    t = T[target]
    if plot == 'skip':                      # give up on this target for now (two mismatches, or no holder line)
        p = os.path.join(HERE, f'holder-clicks-{dist}.csv')
        with open(p, 'a', newline='', encoding='utf-8') as fh:
            csv.writer(fh).writerow([target, 'skip', 'skip', '', t['square'], t['field'], t['group'], t['E'], t['N']])
        print('SKIPPED', target)
        return
    d = math.hypot(float(re_) - float(t['E']), float(rn) - float(t['N']))
    if d > 120:
        print(f'MISMATCH {target}: the click was {d:.0f} m from the target. Close the card and redo this target.')
        sys.exit(1)
    if plot != 'none' and not re.fullmatch(r'\d{11}', plot):
        print('BAD PLOT NUMBER', plot)
        sys.exit(1)
    if holder == '':
        print('NOT-READY: no holder line yet; wait 4 s and read the page text again')
        sys.exit(1)
    if holder not in ('hidden', 'none'):
        holder = clean(holder)
        if re.match(r'^(КФХ|КХ|ФХ|ИП|ЛПХ|ПТ)(\s|$)', holder):      # a peasant farm or a private person: never named
            holder, bin_ = 'hidden', ''
        if holder != 'hidden' and not re.fullmatch(r'\d{12}', bin_):
            print('BAD BIN', bin_)
            sys.exit(1)
    p = os.path.join(HERE, f'holder-clicks-{dist}.csv')
    done = {(r['square'], r['field']) for r in csv.DictReader(open(p, encoding='utf-8'))} if os.path.isfile(p) else set()
    if (t['square'], t['field']) in done:
        print('ALREADY LOGGED', target)
        return
    new = not os.path.isfile(p)
    with open(p, 'a', newline='', encoding='utf-8') as fh:
        w = csv.writer(fh)
        if new:
            w.writerow(['target', 'plot', 'holder', 'bin', 'square', 'field', 'group', 'E', 'N'])
        w.writerow([target, plot, holder, bin_, t['square'], t['field'], t['group'], t['E'], t['N']])
    print(f'OK {target} {plot} {holder} (click {d:.0f} m off)')


if __name__ == '__main__':
    main()
