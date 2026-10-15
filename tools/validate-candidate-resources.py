"""Validate the shared candidate's six translations without an Android SDK."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
LOCALES = ("values", "values-ru-rRU", "values-de", "values-es", "values-fr", "values-it")
PREFIXES = ("profile_safe_", "diagnostics_safe_", "kill_switch_")
FORMAT = re.compile(r"%(?:\d+\$)?[sd]")


def load(locale):
    path = ROOT / "app/src/main/res" / locale / "strings.xml"
    values = {}
    for item in ET.parse(path).getroot():
        name = item.get("name")
        if item.tag != "string" or not name:
            continue
        if name in values:
            raise ValueError(f"{locale}: duplicate string {name}")
        values[name] = "".join(item.itertext())
    return values


def main():
    base = load(LOCALES[0])
    # Include the menu entries by their actual names as well as the three screens.
    menu = {key for key in base if "kill_switch" in key and not key.startswith("kill_switch_")}
    keys = {key for key in base if key.startswith(PREFIXES)} | menu
    if not keys or not all(any(key.startswith(prefix) for key in keys) for prefix in PREFIXES):
        raise ValueError("Missing candidate feature resources")
    for locale in LOCALES:
        values = load(locale)
        for key in sorted(keys):
            text = values.get(key, "")
            if not text.strip():
                raise ValueError(f"{locale}: missing or empty {key}")
            if sorted(FORMAT.findall(text)) != sorted(FORMAT.findall(base[key])):
                raise ValueError(f"{locale}: format arguments differ in {key}")
        if values["kill_switch_steps"].count(r"\n") != base["kill_switch_steps"].count(r"\n"):
            raise ValueError(f"{locale}: instruction separators differ")
        print(f"{locale}: {len(keys)} candidate strings OK")


if __name__ == "__main__":
    main()
