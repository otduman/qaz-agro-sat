"""Tests for record.clean. Run: python test_record.py"""
import record


def test_space_after_an_opening_quote_goes():
    assert record.clean('ТОО " Колос"') == 'ТОО "Колос"'


def test_space_after_a_closing_quote_stays():
    assert record.clean('ГУ "Аппарат акима села Тест" Тестовского района') == \
        'ГУ "Аппарат акима села Тест" Тестовского района'


def test_long_form_becomes_too():
    assert record.clean('Товарищество с ограниченной ответственностью "Тест"') == 'ТОО "Тест"'


def test_long_cooperative_form_becomes_pk():
    assert record.clean('Производственный кооператив "Тест"') == 'ПК "Тест"'


def test_a_doubled_quote_pair_around_the_name_becomes_one():
    assert record.clean('ТОО " "Колос""') == 'ТОО "Колос"'


def test_a_missing_space_after_the_form_is_put_back():
    assert record.clean('ТОО"Колос-STEM"') == 'ТОО "Колос-STEM"'


def test_a_quote_inside_the_name_stays():
    assert record.clean('ТОО "Агрофирма "Колос""') == 'ТОО "Агрофирма "Колос""'


if __name__ == '__main__':
    for k, v in sorted(globals().items()):
        if k.startswith('test_'):
            v()
            print('ok', k)
