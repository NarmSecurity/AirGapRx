#!/usr/bin/env python3
"""
Compile the offline interaction knowledge base into a signed package.

    python kb/build_kb.py                 # development build (uses dev fixture if DDInter absent)
    python kb/build_kb.py --release       # fails if any curated row is unreviewed or DDInter is missing

Inputs (kb/sources/):
  ingredients.csv               curated canonical ingredients: name, kind, display
  synonyms.csv                  brand / generic / Latin names -> canonical ingredient
  supplement_interactions.csv   curated supplement/herbal/food interactions with evidence keys
  ddinter/*.csv                 DDInter downloads (Drug_A, Drug_B, Level columns); see kb/README.md
  dev_fixture_ddi.csv           used only when ddinter/ is empty (labelled DEV-FIXTURE)
  rxnorm/RXNCONSO.RRF, RXNREL.RRF   optional: adds RxCUI identifiers and US brand names

Outputs:
  app/src/main/assets/kb/airgaprx-kb.zip     signed package bundled in the APK
  app/src/main/assets/kb/kb_public_key.pem   publisher public key (APK-embedded trust anchor)
  build/kb/                                   unzipped copy for inspection

The signing key is created on first run at kb/keys/kb_signing_key.pem and is git-ignored.
Requires: Python 3.9+, `pip install cryptography`.
"""
from __future__ import annotations

import argparse
import csv
import glob
import hashlib
import os
import re
import sqlite3
import sys
import unicodedata
import zipfile
from datetime import datetime, timezone

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "kb", "sources")
KEY_PATH = os.path.join(ROOT, "kb", "keys", "kb_signing_key.pem")
ASSET_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "kb")
BUILD_DIR = os.path.join(ROOT, "build", "kb")
KINDS = {"PRESCRIPTION", "OTC", "SUPPLEMENT", "HERBAL", "FOOD"}
SEVERITY_ORDER = {"MAJOR": 0, "MODERATE": 1, "MINOR": 2, "UNKNOWN": 3}


# --- must mirror com.airgaprx.core.normalise.Text.normalise -------------------------------
def normalise(raw: str) -> str:
    s = unicodedata.normalize("NFKD", raw)
    s = "".join(c for c in s if not unicodedata.combining(c)).lower()
    s = re.sub(r"['’ʼ`]", "", s)
    s = re.sub(r"[^a-z0-9]+", " ", s).strip()
    return re.sub(r" +", " ", s)


def slug(name: str) -> str:
    return normalise(name).replace(" ", "-")


def severity(raw: str) -> str:
    r = (raw or "").strip().lower()
    return {"major": "MAJOR", "moderate": "MODERATE", "minor": "MINOR"}.get(r, "UNKNOWN")


def read_csv(path: str) -> list[dict]:
    with open(path, newline="", encoding="utf-8-sig") as f:
        return list(csv.DictReader(f))


def col(row: dict, *names: str) -> str:
    """Case-insensitive column lookup so minor header differences in downloads don't break the build."""
    lower = {k.lower().strip(): v for k, v in row.items() if k}
    for n in names:
        if n.lower() in lower:
            return (lower[n.lower()] or "").strip()
    return ""


class Builder:
    def __init__(self) -> None:
        self.ingredients: dict[str, dict] = {}      # id -> {name, kind, rxcui, ddinter_id}
        self.term_to_id: dict[str, str] = {}        # normalised term -> id
        self.terms: dict[tuple[str, str], int] = {}  # (term, id) -> is_canonical
        self.interactions: dict[tuple, dict] = {}   # (a, b, source) -> row
        self.warnings: list[str] = []
        self.sources: list[str] = []
        self.unreviewed = 0
        self.ddi_source = "none"
        self.ddi_detail = ""

    def warn(self, msg: str) -> None:
        self.warnings.append(msg)

    def add_term(self, term: str, ing_id: str, canonical: bool) -> None:
        norm = normalise(term)
        if not norm:
            return
        existing = self.term_to_id.get(norm)
        if existing and existing != ing_id:
            self.warn(f"term '{norm}' maps to both {existing} and {ing_id}; keeping {existing}")
            return
        self.term_to_id[norm] = ing_id
        self.terms[(norm, ing_id)] = max(self.terms.get((norm, ing_id), 0), 1 if canonical else 0)

    def ensure_ingredient(self, name: str, kind: str = "PRESCRIPTION", ddinter_id: str | None = None) -> str:
        """Resolve a name to an existing ingredient (by name or synonym) or create one."""
        norm = normalise(name)
        if norm in self.term_to_id:
            ing_id = self.term_to_id[norm]
            if ddinter_id and not self.ingredients[ing_id].get("ddinter_id"):
                self.ingredients[ing_id]["ddinter_id"] = ddinter_id
            return ing_id
        ing_id = slug(name)
        display = name.strip()
        self.ingredients[ing_id] = {"name": display[:1].upper() + display[1:], "kind": kind, "rxcui": None, "ddinter_id": ddinter_id}
        self.add_term(name, ing_id, True)
        return ing_id

    def add_interaction(self, a: str, b: str, sev: str, mech: str | None, mgmt: str | None, source: str, ref: str | None) -> None:
        if a == b:
            return
        a, b = sorted((a, b))
        key = (a, b, source)
        prev = self.interactions.get(key)
        if prev is None or SEVERITY_ORDER[sev] < SEVERITY_ORDER[prev["severity"]]:
            self.interactions[key] = {"severity": sev, "mechanism": mech, "management": mgmt, "source": source, "ref": ref}

    # --- inputs ------------------------------------------------------------------------
    def load_curated(self, release: bool) -> None:
        for r in read_csv(os.path.join(SRC, "ingredients.csv")):
            kind = r["kind"].strip().upper()
            if kind not in KINDS:
                sys.exit(f"ingredients.csv: unknown kind {kind} for {r['name']}")
            ing_id = slug(r["name"])
            self.ingredients[ing_id] = {"name": r.get("display") or r["name"], "kind": kind, "rxcui": None, "ddinter_id": None}
            self.add_term(r["name"], ing_id, True)
            if r.get("display"):
                self.add_term(r["display"], ing_id, False)
        for r in read_csv(os.path.join(SRC, "synonyms.csv")):
            target = normalise(r["ingredient"])
            if target not in self.term_to_id:
                sys.exit(f"synonyms.csv: '{r['term']}' points to unknown ingredient '{r['ingredient']}'")
            self.add_term(r["term"], self.term_to_id[target], False)

        unreviewed = 0
        for r in read_csv(os.path.join(SRC, "supplement_interactions.csv")):
            a = self.term_to_id.get(normalise(r["ingredient_a"]))
            b = self.term_to_id.get(normalise(r["ingredient_b"]))
            if not a or not b:
                sys.exit(f"supplement_interactions.csv: unknown ingredient in {r['ingredient_a']} / {r['ingredient_b']}")
            if r.get("reviewed", "").strip().lower() != "yes":
                unreviewed += 1
            self.add_interaction(a, b, severity(r["severity"]), r["mechanism"] or None, r["management"] or None,
                                 "Curated supplement table", r.get("evidence_ref") or None)
        self.unreviewed = unreviewed
        if unreviewed:
            msg = f"{unreviewed} curated interaction rows are not yet reviewed (reviewed != yes)"
            if release:
                sys.exit("RELEASE BLOCKED: " + msg)
            self.warn(msg)
        self.sources.append("Curated supplement and herbal table (see kb/sources/REFERENCES.md)")

    def load_ddinter(self, release: bool) -> None:
        files = sorted(glob.glob(os.path.join(SRC, "ddinter", "*.csv")))
        if not files:
            if release:
                sys.exit("RELEASE BLOCKED: no DDInter CSVs in kb/sources/ddinter/")
            self.warn("DDInter not found: using DEV-FIXTURE drug-drug pairs (development only)")
            files = [os.path.join(SRC, "dev_fixture_ddi.csv")]
            label = "DEV-FIXTURE (not clinical data)"
            self.ddi_source = "DEV-FIXTURE"
            self.ddi_detail = "dev_fixture_ddi.csv"
        else:
            label = "DDInter (CC BY-NC-SA 4.0)"
            self.ddi_source = "DDInter"
            # Download date = oldest file modification time (override with --ddinter-date).
            oldest = min(os.path.getmtime(f) for f in files)
            self.ddi_detail = f"{len(files)} files, downloaded {datetime.fromtimestamp(oldest, timezone.utc).date().isoformat()}"
        n = 0
        for path in files:
            for r in read_csv(path):
                name_a, name_b = col(r, "Drug_A", "drug_a"), col(r, "Drug_B", "drug_b")
                if not name_a or not name_b:
                    continue
                a = self.ensure_ingredient(name_a, ddinter_id=col(r, "DDInterID_A") or None)
                b = self.ensure_ingredient(name_b, ddinter_id=col(r, "DDInterID_B") or None)
                self.add_interaction(a, b, severity(col(r, "Level", "level", "severity")), None, None, label, None)
                n += 1
        print(f"  drug-drug rows read: {n} from {len(files)} file(s)")
        self.sources.append(label)

    def load_rxnorm(self) -> None:
        conso = os.path.join(SRC, "rxnorm", "RXNCONSO.RRF")
        if not os.path.exists(conso):
            return
        in_by_cui: dict[str, str] = {}
        bn_by_cui: dict[str, str] = {}
        with open(conso, encoding="utf-8") as f:
            for line in f:
                p = line.split("|")
                if len(p) < 15 or p[11] != "RXNORM":
                    continue
                cui, tty, name = p[0], p[12], p[14]
                if tty == "IN":
                    in_by_cui[cui] = name
                elif tty == "BN":
                    bn_by_cui[cui] = name
        matched = 0
        for cui, name in in_by_cui.items():
            ing_id = self.term_to_id.get(normalise(name))
            if ing_id and not self.ingredients[ing_id]["rxcui"]:
                self.ingredients[ing_id]["rxcui"] = cui
                matched += 1
        rel = os.path.join(SRC, "rxnorm", "RXNREL.RRF")
        brands = 0
        if os.path.exists(rel):
            cui_to_ing = {v["rxcui"]: k for k, v in self.ingredients.items() if v["rxcui"]}
            with open(rel, encoding="utf-8") as f:
                for line in f:
                    p = line.split("|")
                    if len(p) < 8 or p[7] not in ("tradename_of", "has_tradename"):
                        continue
                    for bn, ing in ((p[0], p[4]), (p[4], p[0])):
                        if bn in bn_by_cui and ing in cui_to_ing:
                            self.add_term(bn_by_cui[bn], cui_to_ing[ing], False)
                            brands += 1
        print(f"  RxNorm: {matched} ingredients matched to RxCUI, {brands} brand links")
        self.sources.append("RxNorm (U.S. National Library of Medicine)")

    # --- outputs -----------------------------------------------------------------------
    def write_sqlite(self, path: str, release_id: str, compiled_at: str) -> None:
        if os.path.exists(path):
            os.remove(path)
        db = sqlite3.connect(path)
        db.executescript(
            """
            PRAGMA journal_mode = DELETE;
            CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE ingredient (id TEXT PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, rxcui TEXT, ddinter_id TEXT);
            CREATE TABLE term (term TEXT NOT NULL, ingredient_id TEXT NOT NULL REFERENCES ingredient(id),
                               is_canonical INTEGER NOT NULL, PRIMARY KEY (term, ingredient_id));
            CREATE TABLE interaction (a_id TEXT NOT NULL, b_id TEXT NOT NULL, severity TEXT NOT NULL,
                                      mechanism TEXT, management TEXT, source TEXT NOT NULL, source_ref TEXT);
            CREATE INDEX idx_interaction_pair ON interaction (a_id, b_id);
            """
        )
        db.executemany("INSERT INTO meta VALUES (?, ?)", [("release_id", release_id), ("compiled_at", compiled_at)])
        db.executemany("INSERT INTO ingredient VALUES (?, ?, ?, ?, ?)",
                       [(k, v["name"], v["kind"], v["rxcui"], v["ddinter_id"]) for k, v in sorted(self.ingredients.items())])
        db.executemany("INSERT INTO term VALUES (?, ?, ?)", [(t, i, c) for (t, i), c in sorted(self.terms.items())])
        db.executemany("INSERT INTO interaction VALUES (?, ?, ?, ?, ?, ?, ?)",
                       [(a, b, v["severity"], v["mechanism"], v["management"], v["source"], v["ref"])
                        for (a, b, _), v in sorted(self.interactions.items())])
        db.commit()
        db.execute("VACUUM")
        db.close()


def export_csv(b: Builder, out_dir: str) -> None:
    """Plain-CSV copy of the compiled KB, read by the JVM evaluation harness (./gradlew :core:evaluate)."""
    with open(os.path.join(out_dir, "ingredients.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f); w.writerow(["id", "name", "kind"])
        for k, v in sorted(b.ingredients.items()):
            w.writerow([k, v["name"], v["kind"]])
    with open(os.path.join(out_dir, "terms.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f); w.writerow(["term", "ingredient_id", "is_canonical"])
        for (t, i), c in sorted(b.terms.items()):
            w.writerow([t, i, c])
    with open(os.path.join(out_dir, "interactions.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f); w.writerow(["a_id", "b_id", "severity", "source"])
        for (a, bb, _), v in sorted(b.interactions.items()):
            w.writerow([a, bb, v["severity"], v["source"]])


def load_or_create_key() -> ec.EllipticCurvePrivateKey:
    if os.path.exists(KEY_PATH):
        with open(KEY_PATH, "rb") as f:
            return serialization.load_pem_private_key(f.read(), password=None)
    os.makedirs(os.path.dirname(KEY_PATH), exist_ok=True)
    key = ec.generate_private_key(ec.SECP256R1())
    with open(KEY_PATH, "wb") as f:
        f.write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    print(f"  created new signing key at {os.path.relpath(KEY_PATH, ROOT)} (keep it private, it is git-ignored)")
    return key


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--release", action="store_true", help="enforce release checks (reviewed rows, DDInter present)")
    ap.add_argument("--release-id", help="override release identifier")
    ap.add_argument("--ddinter-date", help="DDInter download date to record (default: file modification date)")
    args = ap.parse_args()

    now = datetime.now(timezone.utc).replace(microsecond=0)
    compiled_at = now.isoformat().replace("+00:00", "Z")
    release_id = args.release_id or now.strftime("%Y.%m.%d-%H%M") + ("" if args.release else "-dev")

    print("Building knowledge base", release_id)
    b = Builder()
    b.load_curated(args.release)
    b.load_ddinter(args.release)
    b.load_rxnorm()
    if args.ddinter_date and b.ddi_source == "DDInter":
        b.ddi_detail = b.ddi_detail.split(", downloaded")[0] + f", downloaded {args.ddinter_date}"
    # Review status travels inside the *signed* manifest, so the app can refuse development data
    # in a release build. A valid signature proves who built the KB, not that it was reviewed.
    data_status = "release" if args.release else "development"

    os.makedirs(BUILD_DIR, exist_ok=True)
    db_path = os.path.join(BUILD_DIR, "kb.sqlite")
    b.write_sqlite(db_path, release_id, compiled_at)
    with open(db_path, "rb") as f:
        db_sha = hashlib.sha256(f.read()).hexdigest()

    manifest = "\n".join([
        "format_version=1",
        f"release_id={release_id}",
        f"compiled_at={compiled_at}",
        f"db_sha256={db_sha}",
        "sources=" + " | ".join(b.sources),
        f"data_status={data_status}",
        f"unreviewed_rows={b.unreviewed}",
        f"ddi_source={b.ddi_source}",
        f"ddi_source_detail={b.ddi_detail}",
        f"ingredient_count={len(b.ingredients)}",
        f"interaction_count={len(b.interactions)}",
    ]).encode("utf-8") + b"\n"
    key = load_or_create_key()
    signature = key.sign(manifest, ec.ECDSA(hashes.SHA256()))

    with open(os.path.join(BUILD_DIR, "kb.manifest.properties"), "wb") as f:
        f.write(manifest)
    with open(os.path.join(BUILD_DIR, "kb.sig"), "wb") as f:
        f.write(signature)

    os.makedirs(ASSET_DIR, exist_ok=True)
    zip_path = os.path.join(ASSET_DIR, "airgaprx-kb.zip")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        for name in ("kb.sqlite", "kb.manifest.properties", "kb.sig"):
            z.write(os.path.join(BUILD_DIR, name), name)
    with open(os.path.join(ASSET_DIR, "kb_public_key.pem"), "wb") as f:
        f.write(key.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo))

    export_csv(b, BUILD_DIR)
    print(f"  ingredients: {len(b.ingredients)}, terms: {len(b.terms)}, interactions: {len(b.interactions)}")
    print(f"  sha256: {db_sha}")
    print(f"  wrote {os.path.relpath(zip_path, ROOT)} ({os.path.getsize(zip_path) / 1024:.0f} KiB)")
    for w in b.warnings:
        print("  WARNING:", w)


if __name__ == "__main__":
    main()
