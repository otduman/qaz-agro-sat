"""Tests for geo.py and district_data.py. Run from the repository root: python site/build/test_district_data.py"""
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import geo  # noqa: E402
import district_data  # noqa: E402


def test_projection_round_trip():
    p, u = geo.projector(53.85, 69.5), geo.unprojector(53.85, 69.5)
    lon, lat = u(*p(69.9, 53.6))
    assert abs(lon - 69.9) < 1e-5 and abs(lat - 53.6) < 1e-5, (lon, lat)


def test_utm_round_trip():
    lon, lat = geo.from_utm42(*geo.utm42(69.7, 53.9))
    assert abs(lon - 69.7) < 1e-7 and abs(lat - 53.9) < 1e-7, (lon, lat)


def test_names_only_companies_and_cooperatives():
    assert district_data.name('ТОО "Тестовое"', 'company') == 'ТОО «Тестовое»'
    assert district_data.name('ПК "Пробный"', 'cooperative') == 'ПК «Пробный»'
    assert district_data.name('ПТ "Айнур"', 'farm') is None
    assert district_data.name('x', 'holder hidden') is None


def test_name_closes_an_inner_quote_the_cadastre_dropped():
    assert district_data.name('ТОО "СХП "Образец"', 'company') == 'ТОО «СХП "Образец"»'
    assert district_data.name('ТОО "СХП Образец-2"', 'company') == 'ТОО «СХП Образец-2»'


def test_pairs_join_two_registrations_of_one_farm():
    rows = [('ТОО "Новое GCK"', 'company', 'grain', 100.0), ('ТОО "Старое"', 'company', 'oilseed', 50.0),
            ('ТОО "Другое"', 'company', 'grain', 30.0)]
    pairs = {'ТОО "Старое"': 'ТОО "Новое GCK"'}
    merged, labels = district_data.join_pairs(rows, pairs)
    assert merged == [('ТОО "Новое GCK"', 'company', 'grain', 100.0), ('ТОО "Новое GCK"', 'company', 'oilseed', 50.0),
                      ('ТОО "Другое"', 'company', 'grain', 30.0)]
    assert labels == {'ТОО "Новое GCK"': ['ТОО «Старое»']}      # one name per farm; the other registration noted


def test_group_members_fold_into_one_row_with_a_list():
    acc = [dict(name='ТОО «Альфа»', group=True, ha=300, grain=200, oil=100, avg=1.0, tax=10.0, rev=600.0, staff='21–50', whole=None, src=None, url=None, also=[]),
           dict(name='ТОО «Бета»', group=False, ha=250, grain=250, oil=0, avg=2.0, tax=None, rev=None, staff=None, whole=None, src=None, url=None, also=[]),
           dict(name='ТОО «Группа»', group=True, ha=100, grain=60, oil=40, avg=0.5, tax=None, rev=None, staff=None, whole='90 000 га', src='S', url='u', also=[])]
    out = district_data.group_accounts(acc, 'Группа')
    assert [a['name'] for a in out] == ['Группа', 'ТОО «Бета»']
    g = out[0]
    assert g['members_n'] == 2 and g['ha'] == 400 and g['grain'] == 260 and g['oil'] == 140 and g['avg'] == 1.5
    assert g['tax'] == 10.0 and g['rev'] == 600.0 and g['staff'] is None   # sums of the members that have figures
    assert g['whole'] == '90 000 га' and g['url'] == 'u'
    assert 'modern' not in g   # owner 2026-11-17: no modern-fleet estimate
    assert [m['name'] for m in g['members']] == ['ТОО «Альфа»', 'ТОО «Группа»']
    assert g['members'][1]['whole'] is None         # the group's own figure moves up to the group row


def test_a_paired_registration_joins_its_partners_group():
    assert district_data.group_with_pairs({'ТОО "Новое GCK"'}, {'ТОО "Старое"': 'ТОО "Новое GCK"', 'ТОО "Ещё"': 'ТОО "Вне"'})         == {'ТОО "Новое GCK"', 'ТОО "Старое"'}


def test_crops_come_from_the_named_file_per_square():
    import tempfile
    d = tempfile.mkdtemp()
    os.makedirs(f'{d}/E1N1')
    open(f'{d}/E1N1/crops-2026.csv', 'w', encoding='utf-8').write('field,group' + chr(10) + '2026-F0001,grain' + chr(10))
    open(f'{d}/E1N1/crops-2026-v2.csv', 'w', encoding='utf-8').write('field,group' + chr(10) + '2026-F0001,oilseed' + chr(10))
    assert district_data.read_crops(d, '2026') == {('E1N1', '2026-F0001'): 'grain'}
    assert district_data.read_crops(d, '2026', 'crops-{year}-v2.csv') == {('E1N1', '2026-F0001'): 'oilseed'}


def test_plot_outlines_are_projected_and_named_like_companies():
    import json, tempfile
    d = tempfile.mkdtemp()
    ring = [[70.0, 53.0], [70.1, 53.0], [70.1, 53.1], [70.0, 53.0]]
    json.dump({'type': 'FeatureCollection', 'features': [{'type': 'Feature', 'properties': {'holder': 'ТОО "Тестовое"'},
               'geometry': {'type': 'Polygon', 'coordinates': [ring]}}]}, open(f'{d}/p.geojson', 'w', encoding='utf-8'))
    proj = geo.projector(53.0, 70.0)
    out = district_data.plot_outlines([f'{d}/p.geojson'], proj)
    assert out[0]['name'] == 'ТОО «Тестовое»'
    assert out[0]['p'][:2] == [0.0, 0.0] and len(out[0]['p']) == 8


def test_fallow_regions_are_taken_once_from_the_square_whose_core_holds_them():
    import json, tempfile
    d = tempfile.mkdtemp()
    os.makedirs(f'{d}/E500N5900/series-2026'); os.makedirs(f'{d}/E500N5900/fields-2026')
    lon0, lat0 = geo.from_utm42(510000, 5910000)       # inside the core
    lon1, lat1 = geo.from_utm42(500700, 5910000)       # in the 1 km overlap strip, outside the core
    open(f'{d}/squares.csv', 'w').write('square,min_e,min_n,max_e,max_n\nE500N5900,500500,5898500,521500,5919500\n')
    open(f'{d}/E500N5900/series-2026/fields-2026.csv', 'w', encoding='utf-8').write(
        'id,land,area_ha,centroid_lat,centroid_lon\n'
        f'2026-F0001,fallow,50,{lat0},{lon0}\n2026-F0002,fallow,40,{lat1},{lon1}\n2026-F0003,field,90,{lat0},{lon0}\n')
    sq = lambda lo, la: [[lo, la], [lo + 0.01, la], [lo + 0.01, la + 0.01], [lo, la]]
    feats = [{'type': 'Feature', 'properties': {'id': i}, 'geometry': {'type': 'Polygon', 'coordinates': [sq(lo, la)]}}
             for i, lo, la in (('2026-F0001', lon0, lat0), ('2026-F0002', lon1, lat1), ('2026-F0003', lon0, lat0))]
    json.dump({'type': 'FeatureCollection', 'features': feats}, open(f'{d}/E500N5900/fields-2026/fields-2026.geojson', 'w'))
    out = district_data.land_regions(d, '2026', geo.projector(lat0, lon0), 'fallow')
    assert [r['ha'] for r in out] == [50]
    assert len(out[0]['p']) >= 6
    # a square still being processed (not in the district table yet) gives no fallow
    assert district_data.land_regions(d, '2026', geo.projector(lat0, lon0), 'fallow', only={'E520N5900'}) == []


def test_grass_regions_carry_their_use_when_a_use_list_exists():
    # grass by use 2026-11-19 (data/experiments/grass-use-2026-11-19/NOTE.md): pasture near villages and farms,
    # old fields under grass, steppe; without the list a region has no use
    import json, tempfile
    d = tempfile.mkdtemp()
    os.makedirs(f'{d}/E500N5900/fields-2026')
    lon0, lat0 = geo.from_utm42(510000, 5910000)
    sq = lambda lo, la: [[lo, la], [lo + 0.01, la], [lo + 0.01, la + 0.01], [lo, la]]
    feats = [{'type': 'Feature', 'properties': {'id': i}, 'geometry': {'type': 'Polygon', 'coordinates': [sq(lon0, lat0)]}}
             for i in ('2026-F0001', '2026-F0002')]
    json.dump({'type': 'FeatureCollection', 'features': feats}, open(f'{d}/E500N5900/fields-2026/fields-2026.geojson', 'w'))
    open(f'{d}/grass-2026.csv', 'w').write('square,region,land,area_ha\nE500N5900,1,other,60\nE500N5900,2,other,30\n')
    proj = geo.projector(lat0, lon0)
    assert all('u' not in r for r in district_data.grass_regions(d, '2026', proj))
    open(f'{d}/grass-use-2026.csv', 'w').write('square,region,cls\nE500N5900,2,oldfield\n')
    out = district_data.grass_regions(d, '2026', proj)
    assert sorted((r['ha'], r['u']) for r in out) == [(30, 'oldfield'), (60, 'steppe')]   # unlisted: steppe


def test_grass_regions_are_the_listed_ones_whatever_their_label():
    # owner 2026-11-16: one group "grass: hay and pasture" (data/experiments/hay-2026-11-16/NOTE.md)
    import json, tempfile
    d = tempfile.mkdtemp()
    os.makedirs(f'{d}/E500N5900/series-2026'); os.makedirs(f'{d}/E500N5900/fields-2026')
    lon0, lat0 = geo.from_utm42(510000, 5910000)
    open(f'{d}/squares.csv', 'w').write('square,min_e,min_n,max_e,max_n\nE500N5900,500500,5898500,521500,5919500\n')
    open(f'{d}/E500N5900/series-2026/fields-2026.csv', 'w', encoding='utf-8').write(
        'id,land,area_ha,centroid_lat,centroid_lon\n'
        f'2026-F0001,other,60,{lat0},{lon0}\n2026-F0002,fallow,30,{lat0},{lon0}\n2026-F0003,other,90,{lat0},{lon0}\n')
    sq = lambda lo, la: [[lo, la], [lo + 0.01, la], [lo + 0.01, la + 0.01], [lo, la]]
    feats = [{'type': 'Feature', 'properties': {'id': i}, 'geometry': {'type': 'Polygon', 'coordinates': [sq(lon0, lat0)]}}
             for i in ('2026-F0001', '2026-F0002', '2026-F0003')]
    json.dump({'type': 'FeatureCollection', 'features': feats}, open(f'{d}/E500N5900/fields-2026/fields-2026.geojson', 'w'))
    proj = geo.projector(lat0, lon0)
    assert district_data.grass_regions(d, '2026', proj) == []          # no list: no grass
    open(f'{d}/grass-2026.csv', 'w').write('square,region,land,area_ha\nE500N5900,1,other,60\nE500N5900,2,fallow,30\n')
    out = district_data.grass_regions(d, '2026', proj)
    assert sorted(r['ha'] for r in out) == [30, 60] and all(len(r['p']) >= 6 for r in out)
    assert district_data.grass_regions(d, '2026', proj, only={'E520N5900'}) == []
    # Tayynsha 2026-11-17: land outside fields comes from a separate outline file (data/experiments/grass-tayynsha-...)
    other = [{'type': 'Feature', 'properties': {'id': '2026-F100007'},
              'geometry': {'type': 'Polygon', 'coordinates': [sq(lon0 + 0.02, lat0)]}}]
    json.dump({'type': 'FeatureCollection', 'features': other}, open(f'{d}/E500N5900/other-2026.geojson', 'w'))
    open(f'{d}/grass-2026.csv', 'w').write('square,region,land,area_ha\nE500N5900,100007,other,75\n')
    out = district_data.grass_regions(d, '2026', proj)
    assert [r['ha'] for r in out] == [75] and len(out[0]['p']) >= 6
    # 2026-11-18: grass that was a crop field the season before leaves the layer
    # (data/experiments/grass-history-2026-11-18/NOTE.md)
    open(f'{d}/grass-2026.csv', 'w').write('square,region,land,area_ha\nE500N5900,1,other,60\nE500N5900,2,fallow,30\n')
    os.remove(f'{d}/E500N5900/other-2026.geojson')
    open(f'{d}/grass-was-field-2026.csv', 'w').write('square,region,area_ha,share,season\nE500N5900,1,60,0.9,2025\n')
    assert [r['ha'] for r in district_data.grass_regions(d, '2026', proj)] == [30]


def test_fallow_made_crop_becomes_a_cereal_field_with_its_dates():
    # Esil 2026-11-17: thin dry-land crops the map called fallow (data/experiments/esil-fallow-crop-2026-11-17)
    import tempfile
    d = tempfile.mkdtemp()
    assert district_data.fallow_crops(d, '2026') == {}
    open(f'{d}/fallow-crop-2026.csv', 'w').write('square,region,area_ha,status,cut_from,cut_to,cut_middle\n'
                                                 'E1,7,120.4,dated,2026-08-10,2026-08-12,2026-08-11\n')
    out = district_data.fallow_crops(d, '2026')
    assert out == {('E1', '2026-F0007'): dict(ha=120, c='grain', s='dated', f='2026-08-10', t='2026-08-12', m='2026-08-11')}


def test_a_field_drawn_inside_another_fields_outline_joins_it():
    # Esil 2025 (2026-11-17): an 82 ha field sat in the gap between the pieces of a joined 739 ha field
    big = dict(p=[0, 0, 10, 0, 10, 10, 0, 10], ha=700, c='grain', h='ТОО "Тест"', _k=('A', '1'))
    inner = dict(p=[4, 4, 6, 4, 6, 6, 4, 6], ha=80, c='grain', h=None, _k=('A', '2'))
    edge = dict(p=[9, 9, 12, 9, 12, 12, 9, 12], ha=60, c='grain', h=None, _k=('A', '3'))
    out, alias = district_data.fold_inside([big, inner, edge])
    assert [f['ha'] for f in out] == [780, 60] and alias == {('A', '2'): ('A', '1')}


def test_strict_fields_drop_small_ones_and_move_no_crop_ones_to_fallow():
    fields = [dict(p=[0, 0, 1, 0, 1, 1], ha=120, c='grain'), dict(p=[2, 2, 3, 2, 3, 3], ha=30, c='grain'),
              dict(p=[4, 4, 5, 4, 5, 5], ha=200, c='none')]
    fallow = [dict(p=[9, 9, 8, 9, 8, 8], ha=40)]
    kept, fal = district_data.strict_fields(fields, fallow, min_ha=50)
    assert [f['ha'] for f in kept] == [120]
    assert sorted(f['ha'] for f in fal) == [40, 200] and all(set(f) == {'p', 'ha'} for f in fal)


def test_fallow_two_seasons_running_is_read_as_idle_land():
    import tempfile
    d = tempfile.mkdtemp()
    assert district_data.idle_fallow(d, '2026') == set()
    with open(f'{d}/fallow-both-2026.csv', 'w') as fh:
        fh.write('square,region\nE500N5900,2026-F0007\n')
    assert district_data.idle_fallow(d, '2026') == {('E500N5900', '2026-F0007')}
    # fallow green or damp in spring is steppe (Esil, owner 2026-11-15): not farmland either
    with open(f'{d}/fallow-steppe-2026.csv', 'w') as fh:
        fh.write('square,region\nE500N5900,2026-F0009\n')
    assert district_data.idle_fallow(d, '2026') == {('E500N5900', '2026-F0007'), ('E500N5900', '2026-F0009')}


def test_an_island_is_the_region_almost_all_of_whose_border_touches_one_bigger_region():
    import numpy as np
    r = np.ones((10, 10), dtype=np.int64)       # region 1, a big field
    r[4:6, 4:6] = 2                              # region 2: an island inside it
    r[0:3, 7:10] = 3                             # region 3: in a corner, touching field 1 on two sides only
    r[0:3, 9] = 0                                # and the edge of the map
    parents = district_data.island_parents(r, small_px=0)   # the main rule alone (no small-piece rule)
    assert parents == {2: 1}


def test_an_island_without_a_holder_takes_the_holder_of_the_field_around_it():
    owner = {('A', '2026-F0001'): 'ТОО "Тестовое"', ('A', '2026-F0007'): 'ТОО "Другое"'}
    keys = [('A', '2026-F0001'), ('A', '2026-F0002'), ('A', '2026-F0007'), ('A', '2026-F0009')]
    parents = {'A': {2: 1, 7: 1, 9: 5}}          # 7 already has its own holder; 9's parent has none
    out = district_data.inherit_from_parents(owner, parents, keys, '2026')
    assert out[('A', '2026-F0002')] == 'ТОО "Тестовое"'
    assert out[('A', '2026-F0007')] == 'ТОО "Другое"'
    assert ('A', '2026-F0009') not in out


def test_an_island_field_is_folded_into_the_field_around_it():
    keyed = [(('A', '2026-F0001'), {'ha': 300, 'c': 'grain'}), (('A', '2026-F0002'), {'ha': 12, 'c': 'oilseed'}),
             (('A', '2026-F0003'), {'ha': 8, 'c': 'grain'}), (('B', '2026-F0001'), {'ha': 50, 'c': 'grain'})]
    parents = {'A': {2: 1, 3: 9}}                 # 3's parent is not a field in the table: stays as it is
    out = district_data.fold_islands(keyed, parents, '2026')
    assert [f['ha'] for f in out] == [312, 8, 50]
    assert out[0]['c'] == 'grain'


def test_a_small_piece_two_thirds_inside_a_much_bigger_field_also_counts_as_an_island():
    import numpy as np
    r = np.ones((40, 40), dtype=np.int64)        # region 1, big
    r[0:3, 20:23] = 2                            # region 2: 9 px on the top edge, 2/3 of its border on field 1
    r[0:3, 23] = 5                               # (region 5 takes the other third)
    r[0:3, 0:3] = 3                              # region 3: corner piece, only half its border on field 1 ... with
    r[0:3, 3] = 4                                # region 4 beside it taking the other part
    parents = district_data.island_parents(r, small_px=50)
    assert parents.get(2) == 1 and 3 not in parents


def test_a_region_dark_in_swir_all_summer_is_water_not_land():
    import tempfile
    d = tempfile.mkdtemp()
    os.makedirs(f'{d}/SQ/series-2026')
    lines = ['field,date,clear_share,b11']
    for day in ('06-05', '06-20', '07-05', '07-20', '08-05'):
        lines += [f'F1,2026-{day},1.0,0.06', f'F2,2026-{day},1.0,0.24', f'F3,2026-{day},0.5,0.06']
    lines += ['F2,2026-05-01,1.0,0.05', 'F4,2026-06-05,1.0,0.05']    # spring, and too few summer dates
    open(f'{d}/SQ/series-2026/series-2026.csv', 'w').write(chr(10).join(lines))
    assert district_data.dark_regions(d, 2026, ['SQ', 'NOSUCH']) == {('SQ', 'F1')}


def test_joined_pieces_become_one_field_with_their_hectares_and_the_bigger_crop():
    keyed = [(('S', 'F1'), dict(p=[0, 0], ha=100, c='grain', h=None, k=None, s='dated', m='2026-08-20')),
             (('S', 'F2'), dict(p=[1, 1], ha=150, c='oilseed', h='ТОО "А"', k='company', s='dated', m='2026-08-22')),
             (('S', 'F3'), dict(p=[2, 2], ha=40, c='grain', h=None, k=None, s='no cut found', m=''))]
    joins = [dict(square='S', members=['F1', 'F2', 'F3'], keep='F2', p=[9, 9, 8, 8])]
    out, alias = district_data.join_fields(keyed, joins)
    assert len(out) == 1
    k, f = out[0]
    assert k == ('S', 'F2') and f['ha'] == 290 and f['c'] == 'oilseed' and f['p'] == [9, 9, 8, 8]
    assert f['h'] == 'ТОО "А"' and f['m'] == '2026-08-22'
    assert alias == {('S', 'F1'): ('S', 'F2'), ('S', 'F3'): ('S', 'F2')}


def test_grass_or_fallow_lying_inside_a_drawn_field_is_not_drawn():
    # islands belong to the field around them (owner 2026-11-13; square-fields-2026-11-18 part J v3)
    fields = [dict(p=[0, 0, 10, 0, 10, 10, 0, 10], ha=1000)]
    patches = [dict(p=[4, 4, 6, 4, 6, 6, 4, 6], ha=4), dict(p=[20, 20, 22, 20, 22, 22], ha=2)]
    assert district_data.outside_fields(patches, fields) == [patches[1]]


def test_a_split_field_is_drawn_as_its_pieces_with_the_hectares_shared():
    # squares first part S 2026-11-18 (data/experiments/square-fields-2026-11-18): one field cut in two along a line
    # that shows on the photos; both pieces keep its crop, dates and holder
    fields = [dict(p=[0, 0, 4, 0, 4, 4], ha=100, c='grain', h='ТОО "А"', k='company', s='dated', m='2026-08-20',
                   _k=('S', 'F1')),
              dict(p=[9, 9, 8, 8], ha=50, c='oilseed', h=None, k=None, s='dated', m='', _k=('S', 'F2'))]
    splits = {('S', 'F1'): [dict(p=[0, 0, 2, 0, 2, 4], share=0.6), dict(p=[2, 0, 4, 0, 4, 4], share=0.4)]}
    out = district_data.split_fields(fields, splits)
    assert [f['ha'] for f in out] == [60, 40, 50]
    assert [f['p'] for f in out[:2]] == [[0, 0, 2, 0, 2, 4], [2, 0, 4, 0, 4, 4]]
    assert all(f['c'] == 'grain' and f['h'] == 'ТОО "А"' and f['_k'] == ('S', 'F1') for f in out[:2])


def test_a_piece_split_along_a_seen_line_carries_its_own_crop_and_harvest_date():
    # lines first 2026-11-19 (data/experiments/lines-first-2026-11-18): pieces read like any field (piece_facts)
    fields = [dict(p=[0, 0, 4, 0, 4, 4], ha=100, c='grain', h='ТОО "А"', k='company', s='dated', f='2026-08-10',
                   t='2026-08-12', m='2026-08-11', _k=('S', 'F1'))]
    splits = {('S', 'F1'): [dict(p=[0, 0, 2, 0, 2, 4], share=0.5),
                            dict(p=[2, 0, 4, 0, 4, 4], share=0.5, c='oilseed', s='dated', f='2026-09-20',
                                 t='2026-09-22', m='2026-09-21')]}
    a, b = district_data.split_fields(fields, splits)
    assert a['c'] == 'grain' and a['m'] == '2026-08-11'
    assert b['c'] == 'oilseed' and (b['s'], b['f'], b['t'], b['m']) == ('dated', '2026-09-20', '2026-09-22', '2026-09-21')
    assert b['h'] == 'ТОО "А"' and b['ha'] == 50


def test_split_files_are_read_with_every_piece_and_earlier_splits_win():
    import json, tempfile
    d = tempfile.mkdtemp()
    lon0, lat0 = 70.0, 53.0
    sq = lambda lo: [[lo, lat0], [lo + 0.01, lat0], [lo + 0.01, lat0 + 0.01], [lo, lat0]]
    feat = lambda keep, piece, share, lo, **k: {'type': 'Feature', 'geometry': {'type': 'Polygon', 'coordinates': [sq(lo)]},
                                               'properties': dict(square='S', keep=keep, piece=piece, share=share, **k)}
    json.dump({'type': 'FeatureCollection', 'features': [feat('F1', 1, 0.5, lon0), feat('F1', 2, 0.5, lon0 + 0.01)]},
              open(f'{d}/splits-2026.geojson', 'w'))
    json.dump({'type': 'FeatureCollection', 'features': [
        feat('F1', 1, 0.3, lon0, c='grain'), feat('F1', 2, 0.7, lon0 + 0.01, c='grain'),
        feat('F2', 1, 0.4, lon0 + 0.02, c='grain', m='2026-08-01'),
        feat('F2', 2, 0.6, lon0 + 0.03, c='oilseed', m='2026-09-01')]}, open(f'{d}/line-splits-2026.geojson', 'w'))
    out = district_data.read_splits(d, '2026', geo.projector(lat0, lon0))
    assert [q['share'] for q in out[('S', 'F1')]] == [0.5, 0.5]          # the earlier split wins
    assert [q['c'] for q in out[('S', 'F2')]] == ['grain', 'oilseed'] and out[('S', 'F2')][1]['m'] == '2026-09-01'


def test_a_small_field_whose_outline_is_a_seen_line_stays_on_the_map():
    # evidence joins and small fields 2026-11-19 (data/experiments/lines-first-2026-11-18): under 50 ha is not dropped
    # when it is a real small field (small-fields-YEAR.csv)
    fields = [dict(p=[0, 0], ha=30, c='grain', _k=('S', 'F1')), dict(p=[0, 0], ha=30, c='grain', _k=('S', 'F2'))]
    kept, _ = district_data.strict_fields(fields, [], keep_small={('S', 'F1')})
    assert [f['_k'] for f in kept] == [('S', 'F1')]


def test_simple_outlines_change_the_drawing_only():
    # simple outlines v4 2026-11-19 (data/experiments/lines-first-2026-11-18): bays drawn in, hectares unchanged
    fields = [dict(p=[0, 0, 1, 1], ha=100, c='grain', _k=('S', 'F1')),
              dict(p=[2, 2, 3, 3], ha=40, c='grain', _k=('S', 'F2'), _piece=1),
              dict(p=[4, 4, 5, 5], ha=60, c='oilseed', _k=('S', 'F2'), _piece=2)]
    outlines = {('S', 'F1', 0): [9, 9, 9, 8], ('S', 'F2', 2): [7, 7, 7, 6]}
    out = district_data.draw_simple(fields, outlines)
    assert [f['p'] for f in out] == [[9, 9, 9, 8], [2, 2, 3, 3], [7, 7, 7, 6]]
    assert [f['ha'] for f in out] == [100, 40, 60]


def test_split_pieces_remember_their_piece_number():
    fields = [dict(p=[0, 0], ha=100, c='grain', _k=('S', 'F1'))]
    out = district_data.split_fields(fields, {('S', 'F1'): [dict(p=[1], share=0.5, piece=3), dict(p=[2], share=0.5, piece=4)]})
    assert [f['_piece'] for f in out] == [3, 4]


def test_a_one_piece_feature_gives_the_field_its_straightened_outline():
    # squares first 2026-11-18 (data/experiments/square-fields-2026-11-18): the outline changes, hectares do not
    keyed = [(('S', 'F1'), dict(p=[0, 0, 1, 1], ha=100, c='grain', h=None, k=None, s='dated', m='2026-08-20'))]
    out, alias = district_data.join_fields(keyed, [dict(square='S', members=['F1'], keep='F1', p=[5, 5, 6, 6])])
    assert out == [(('S', 'F1'), dict(keyed[0][1], p=[5, 5, 6, 6]))] and alias == {}


def test_a_join_shares_the_one_known_holder_with_every_piece():
    joins = [dict(square='S', members=['F1', 'F2'], keep='F1', p=[]), dict(square='S', members=['F3', 'F4'], keep='F3', p=[])]
    owner = {('S', 'F2'): 'A', ('S', 'F3'): 'B', ('S', 'F4'): 'C'}
    assert district_data.share_group_holders(owner, joins) == {('S', 'F1'): 'A', ('S', 'F2'): 'A', ('S', 'F3'): 'B',
                                                               ('S', 'F4'): 'C'}


def test_an_island_inside_a_joined_piece_folds_into_the_joined_field():
    host = dict(ha=300)
    keyed = [(('S', '2026-F0002'), host), (('S', '2026-F0009'), dict(ha=5))]
    out = district_data.fold_islands(keyed, {'S': {9: 1}}, 2026, alias={('S', '2026-F0001'): ('S', '2026-F0002')})
    assert out == [host] and host['ha'] == 305


def test_a_field_counted_by_two_squares_is_drawn_once_and_keeps_its_holder():
    import tempfile
    d = tempfile.mkdtemp()
    open(f'{d}/duplicates-2026.csv', 'w', encoding='utf-8').write(
        chr(10).join(['square,region,kept_square,kept_region', 'A,2026-F0001,B,2026-F0009', 'A,2026-F0002,B,2026-F0008']))
    skip, kept = district_data.duplicates_of(d, 2026)
    assert skip == {('A', '2026-F0001'), ('A', '2026-F0002')}
    owner = district_data.move_holders({('A', '2026-F0001'): 'X', ('B', '2026-F0008'): 'Y', ('A', '2026-F0002'): 'Z'}, kept)
    assert owner[('B', '2026-F0009')] == 'X' and owner[('B', '2026-F0008')] == 'Y'
    assert district_data.duplicates_of(tempfile.mkdtemp(), 2026) == (set(), {})


def test_pace_splits_group_and_other():
    rows = [('2026-08-05', 100.0, True), ('2026-08-25', 300.0, False), ('', 100.0, False)]
    pace = district_data.pace(rows, 2026)
    by = dict(pace['group'])
    assert by['2026-08-06'] == 1.0, by['2026-08-06']
    by = dict(pace['other'])
    assert by['2026-08-26'] == 0.75, by['2026-08-26']
    by = dict(pace['all'])
    assert by['2026-08-26'] == 0.8, by['2026-08-26']


def test_whole_farm_missing_file_is_empty():
    with tempfile.TemporaryDirectory() as tmp:
        assert district_data.public_figures(tmp) == {}


def test_tayynsha_build_privacy():
    if not os.path.isfile('data/tayynsha/holders.csv'):
        print('  skipped: no data/')
        return
    d = district_data.build('data/tayynsha', '2026', 'data/tayynsha/holders.csv', 'data/tayynsha/company-groups.csv',
                            'docs/districts/tayynsha.geojson', (69.764, 53.848), 701)
    assert len(d["fields"]) == 4037, len(d["fields"])  # E640N5960 dropped 2026-11-20 (owner: tidy only, 5% inside); crop seen v3 2026-11-20: a no-crop field found sown (+2 pieces); edge squares 2026-11-20: 13 more squares (47), fields beyond the border shown as on the other rectangles; lines first 2026-11-19: 415 fields split along seen lines into 934 pieces, 90 small fields (20-50 ha) kept, simple outlines; 23 fields split along inner corners (2026-11-18), under 50 ha and no-crop fields left off, gaps filled, tails trimmed, small fields joined, hybrid outlines, straight borders, holders via shared borders, readability joins, duplicates dropped, joins v3, islands folded
    assert all(f['h'] is None for f in d['fields'] if f['k'] in ('farm', 'private', 'lease', 'college'))
    assert d['totals']['group_ha'] > 0
    # 'known holder' leaves out land whose holder the map hides: group share as published (about 42%)
    t = d['totals']
    assert t['known_ha'] < t['linked_ha'] and 0.40 < t['group_ha'] / t['known_ha'] < 0.44, t
    assert d['accounts'][0]['ha'] >= d['accounts'][-1]['ha']
    # company hectares come only from the fields shown (no left-off small or no-crop fields)
    assert 400000 < sum(a["ha"] for a in d["accounts"]) < 405000, sum(a["ha"] for a in d["accounts"])   # 406,928 with them; 403,415 after the edge squares (fields at the old edge re-cut)
    assert all('_k' not in f for f in d['fields'])
    assert set(d['sizes']) <= {'group', 'company', 'farm', 'unread'}
    assert abs(sum(d['crop_ha'].values()) - d['totals']['ha']) < 50


def test_registry_figures_read_taxes_revenue_and_staff_per_holder():
    with tempfile.TemporaryDirectory() as tmp:
        assert district_data.registry_figures(tmp) == {}
        with open(os.path.join(tmp, 'company-registry.csv'), 'w', encoding='utf-8', newline='') as f:
            f.write('holder,tax_2025,revenue_2025,staff,url\n"ТОО ""А""",354000000,21900000000,251–500,https://x\n"ТОО ""Б""",,,,\n')
        got = district_data.registry_figures(tmp)
        assert got['ТОО "А"'] == {'tax': 354.0, 'rev': 21.9, 'staff': '251–500', 'reg_url': 'https://x'}   # mln and bn tenge
        assert got['ТОО "Б"'] == {'tax': None, 'rev': None, 'staff': None, 'reg_url': None}


if __name__ == '__main__':
    for n, f in list(globals().items()):
        if n.startswith('test_'):
            f()
            print('ok', n)
    print('all tests passed')