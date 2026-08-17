#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Валидатор сессий TouchRecorder-mini.

Запуск:
    python validate.py session.json

Только стандартная библиотека (json, sys, statistics), без pip-зависимостей.
Код выхода: 0 — проверки пройдены, 1 — есть ошибки валидации.
"""

import json
import statistics
import sys

ACTIONS = ("down", "move", "up", "cancel")

# Поля события: имя -> допустимые типы. None разрешён у pressure/size и т.п.:
# драйверы некоторых устройств не отдают величину, и приложение пишет null.
EVENT_FIELDS = {
    "action": (str,),
    "event_time_ms": (int,),
    "proc_time_ns": (int,),
    "x": (int, float),
    "y": (int, float),
    "pressure": (int, float, type(None)),
    "size": (int, float, type(None)),
    "touch_major": (int, float, type(None)),
    "touch_minor": (int, float, type(None)),
    "history": (list,),
}

HISTORY_FIELDS = {
    "event_time_ms": (int,),
    "x": (int, float),
    "y": (int, float),
    "pressure": (int, float, type(None)),
    "size": (int, float, type(None)),
}

DEVICE_FIELDS = {
    "manufacturer": (str,),
    "model": (str,),
    "sdk_int": (int,),
    "app_version": (str,),
}


class Report:
    def __init__(self):
        self.errors = []
        self.warnings = []

    def error(self, message):
        self.errors.append(message)

    def warn(self, message):
        self.warnings.append(message)


def check_type(report, where, container, field, types):
    """Проверяет наличие поля и его тип. Возвращает True, если поле пригодно к разбору."""
    if field not in container:
        report.error("%s: отсутствует обязательное поле '%s'" % (where, field))
        return False
    value = container[field]
    # bool — подкласс int, но в наших полях это всегда ошибка.
    if isinstance(value, bool) or not isinstance(value, types):
        report.error(
            "%s: поле '%s' имеет тип %s, ожидался %s"
            % (where, field, type(value).__name__, "/".join(t.__name__ for t in types))
        )
        return False
    return True


def validate_meta(report, session):
    """Проверка 1: обязательные поля верхнего уровня и метаданные устройства."""
    check_type(report, "session", session, "session_id", (str,))
    check_type(report, "session", session, "started_at_ms", (int,))

    if check_type(report, "session", session, "device", (dict,)):
        device = session["device"]
        for field, types in DEVICE_FIELDS.items():
            check_type(report, "device", device, field, types)
        if check_type(report, "device", device, "screen", (dict,)):
            screen = device["screen"]
            check_type(report, "device.screen", screen, "width_px", (int,))
            check_type(report, "device.screen", screen, "height_px", (int,))


def validate_event(report, where, event):
    """Проверка 1 для события + проверка 3 для его исторических сэмплов."""
    ok = True
    for field, types in EVENT_FIELDS.items():
        if not check_type(report, where, event, field, types):
            ok = False
    if not ok:
        return False

    if event["action"] not in ACTIONS:
        report.warn("%s: неизвестное значение action '%s'" % (where, event["action"]))

    parent_time = event["event_time_ms"]
    previous = None
    for i, sample in enumerate(event["history"]):
        sample_where = "%s.history[%d]" % (where, i)
        if not isinstance(sample, dict):
            report.error("%s: сэмпл не является объектом" % sample_where)
            ok = False
            continue

        sample_ok = True
        for field, types in HISTORY_FIELDS.items():
            if not check_type(report, sample_where, sample, field, types):
                sample_ok = False
        if not sample_ok:
            ok = False
            continue

        time_ms = sample["event_time_ms"]
        if previous is not None and time_ms < previous:
            report.error(
                "%s: время в history убывает (%d после %d)" % (sample_where, time_ms, previous)
            )
            ok = False
        if time_ms > parent_time:
            report.error(
                "%s: время сэмпла %d больше времени родительского события %d"
                % (sample_where, time_ms, parent_time)
            )
            ok = False
        previous = time_ms

    return ok


def validate_gestures(report, session):
    """Проверки 1 и 2: структура жестов и неубывание event_time_ms внутри жеста."""
    if not check_type(report, "session", session, "gestures", (list,)):
        return []

    gestures = session["gestures"]
    if not gestures:
        report.warn("session: список жестов пуст")

    for g_index, gesture in enumerate(gestures):
        where = "gestures[%d]" % g_index
        if not isinstance(gesture, dict):
            report.error("%s: жест не является объектом" % where)
            continue
        check_type(report, where, gesture, "gesture_id", (int,))
        if not check_type(report, where, gesture, "events", (list,)):
            continue

        events = gesture["events"]
        if not events:
            report.error("%s: жест не содержит событий" % where)
            continue

        previous = None
        for e_index, event in enumerate(events):
            event_where = "%s.events[%d]" % (where, e_index)
            if not isinstance(event, dict):
                report.error("%s: событие не является объектом" % event_where)
                continue
            if not validate_event(report, event_where, event):
                continue

            time_ms = event["event_time_ms"]
            if previous is not None and time_ms < previous:
                report.error(
                    "%s: event_time_ms убывает (%d после %d)" % (event_where, time_ms, previous)
                )
            previous = time_ms

        if events and isinstance(events[0], dict) and events[0].get("action") != "down":
            report.warn("%s: жест начинается не с 'down'" % where)
        if events and isinstance(events[-1], dict) and events[-1].get("action") not in ("up", "cancel"):
            report.warn("%s: жест не завершён 'up'/'cancel' (экспорт в середине жеста?)" % where)

    return gestures


def constant_field_warnings(report, gestures):
    """Проверка 5: константность pressure / size / touch_major по всей сессии."""
    for field in ("pressure", "size", "touch_major"):
        values = [
            event[field]
            for gesture in gestures
            if isinstance(gesture, dict)
            for event in gesture.get("events", [])
            if isinstance(event, dict) and isinstance(event.get(field), (int, float))
            and not isinstance(event.get(field), bool)
        ]
        if len(values) >= 2 and min(values) == max(values):
            report.warn(
                "поле %s константно (%s) — вероятно, ограничение устройства" % (field, values[0])
            )


def print_statistics(session, gestures):
    """Проверка 4: итоговая статистика в stdout."""
    events = [
        event
        for gesture in gestures
        if isinstance(gesture, dict)
        for event in gesture.get("events", [])
        if isinstance(event, dict)
    ]
    history_total = sum(len(e.get("history", [])) for e in events)
    moves = [e for e in events if e.get("action") == "move"]
    moves_with_history = [e for e in moves if e.get("history")]

    device = session.get("device", {}) if isinstance(session.get("device"), dict) else {}
    screen = device.get("screen", {}) if isinstance(device.get("screen"), dict) else {}

    print("=== Сессия ===")
    print("session_id:      %s" % session.get("session_id"))
    print("started_at_ms:   %s" % session.get("started_at_ms"))
    print(
        "устройство:      %s %s (SDK %s), экран %sx%s, версия приложения %s"
        % (
            device.get("manufacturer"),
            device.get("model"),
            device.get("sdk_int"),
            screen.get("width_px"),
            screen.get("height_px"),
            device.get("app_version"),
        )
    )

    print()
    print("=== Итоги ===")
    print("жестов:                  %d" % len(gestures))
    print("событий:                 %d" % len(events))
    print("исторических сэмплов:    %d" % history_total)
    if moves:
        share = len(moves_with_history) / len(moves) * 100.0
        print(
            "move с непустым history: %d из %d (%.1f%%)"
            % (len(moves_with_history), len(moves), share)
        )
    else:
        print("move с непустым history: событий move нет")
    if moves_with_history:
        per_move = [len(e["history"]) for e in moves_with_history]
        print("сэмплов на такой move:   среднее %.2f, максимум %d"
              % (statistics.mean(per_move), max(per_move)))

    print()
    print("=== Жесты ===")
    durations = []
    for gesture in gestures:
        if not isinstance(gesture, dict):
            continue
        gesture_events = [e for e in gesture.get("events", []) if isinstance(e, dict)]
        if not gesture_events:
            continue
        times = [e["event_time_ms"] for e in gesture_events if isinstance(e.get("event_time_ms"), int)]
        duration = (max(times) - min(times)) if times else 0
        durations.append(duration)
        g_history = sum(len(e.get("history", [])) for e in gesture_events)
        print(
            "жест %-3s длительность %5d мс, событий %4d, сэмплов %4d"
            % (gesture.get("gesture_id"), duration, len(gesture_events), g_history)
        )
    if len(durations) >= 2:
        print("средняя длительность жеста: %.1f мс" % statistics.mean(durations))


def main(argv):
    if len(argv) != 2:
        print("Использование: python validate.py session.json", file=sys.stderr)
        return 1

    path = argv[1]
    report = Report()

    try:
        with open(path, "r", encoding="utf-8") as handle:
            session = json.load(handle)
    except OSError as exc:
        print("ERROR: не удалось открыть файл: %s" % exc, file=sys.stderr)
        return 1
    except json.JSONDecodeError as exc:
        print("ERROR: файл не является валидным JSON: %s" % exc, file=sys.stderr)
        return 1

    if not isinstance(session, dict):
        print("ERROR: корневой элемент JSON не является объектом", file=sys.stderr)
        return 1

    validate_meta(report, session)
    gestures = validate_gestures(report, session)
    constant_field_warnings(report, gestures)

    print_statistics(session, gestures)

    if report.warnings:
        print()
        for message in report.warnings:
            print("WARNING: %s" % message)

    print()
    if report.errors:
        for message in report.errors:
            print("ERROR: %s" % message, file=sys.stderr)
        print("РЕЗУЛЬТАТ: провалено, ошибок — %d" % len(report.errors))
        return 1

    print("РЕЗУЛЬТАТ: все проверки пройдены")
    return 0


if __name__ == "__main__":
    try:
        # Русский текст в консоли Windows (cp866/cp1251) иначе падает на UnicodeEncodeError.
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except (AttributeError, OSError):
        pass
    sys.exit(main(sys.argv))
