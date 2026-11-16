"""Tests for record.clean. Run: python test_record.py"""
import record


def test_space_after_an_opening_quote_goes():
    assert record.clean('ТОО " Колос"') == 'ТОО "Колос"'


def test_space_after_a_closing_quote_stays():
    assert record.clean('ГУ "Аппарат акима села Тест" Тестовского района') == \
        'ГУ "Аппарат акима села Тест" Тестовского района'


def test_long_form_becomes_too():
    assert record.clean('Товарищество с ограниченной ответственностью "Тест"') == 'ТОО "Тест"'


if __name__ == '__main__':
    for k, v in sorted(globals().items()):
        if k.startswith('test_'):
            v()
            print('ok', k)
