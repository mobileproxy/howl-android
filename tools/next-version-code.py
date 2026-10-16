"""Keep normal CI numbering while exceeding separately published builds."""
import json
import os
from pathlib import Path
import urllib.request


def next_code(base, run, published):
    if any(type(value) is not int or value < 0 for value in (base, run, published)):
        raise ValueError("Version inputs must be nonnegative integers")
    result = max(base + run, published + 1)
    if result > 2100000000:
        raise ValueError("Android versionCode limit exceeded")
    return result


if __name__ == "__main__":
    props = dict(line.split("=", 1) for line in
                 (Path(__file__).resolve().parents[1] / "version.properties").read_text().splitlines()
                 if "=" in line and not line.startswith("#"))
    with urllib.request.urlopen("https://gethowl.app/downloads/howl/latest.json", timeout=30) as response:
        published = json.load(response)["versionCode"]
    print(next_code(int(props["VERSION_CODE"]), int(os.environ["GITHUB_RUN_NUMBER"]), published))
