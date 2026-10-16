"""Generate the six shared connection-check translations without an Android SDK."""
from pathlib import Path
import json
import re
import xml.etree.ElementTree as E

ROOT = Path(__file__).resolve().parents[1]
for lang, values in json.loads((ROOT / "tools/connection-check-strings.json").read_text(encoding="utf-8")).items():
    resource = E.Element("resources")
    for key, text in values.items():
        kind = "s" if key == "checks_checked_at" else "d"
        text = re.sub(r"\{(\d+)\}", lambda m: f"%{int(m[1])+1}${kind}", text)
        E.SubElement(resource, "string", name=key).text = text.replace("'", r"\'")
    E.indent(resource, space="    ")
    locale = "values" if lang == "en" else "values-ru-rRU" if lang == "ru" else f"values-{lang}"
    folder = ROOT / "app/src/main/res" / locale
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "howl_connection_checks.xml").write_text('<?xml version="1.0" encoding="utf-8"?>\n' + E.tostring(resource, encoding="unicode") + "\n", encoding="utf-8")
print("26 connection-check strings x 6 languages generated")
