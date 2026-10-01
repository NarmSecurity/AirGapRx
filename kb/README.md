# Knowledge base

`build_kb.py` compiles the sources in `kb/sources/` into one signed package. The app bundles that
package in `app/src/main/assets/kb/airgaprx-kb.zip`.

```
kb.sqlite                 ingredient, term (synonyms/brands), interaction, meta
kb.manifest.properties    release_id, compiled_at, db_sha256, sources, counts
kb.sig                    ECDSA P-256 / SHA-256 signature over the manifest
```

On first launch, and for every offline update, the app checks the signature against the public key
built into the APK. It then checks the database's SHA-256 against the manifest and rejects older
releases. The installed copy is swapped atomically, so a rejected package leaves the previous KB in
place (criterion 6).

## Building

```bash
pip install cryptography
python kb/build_kb.py            # development build
python kb/build_kb.py --release  # refuses to build if DDInter is missing or any curated row is unreviewed
```

The first run creates a signing key at `kb/keys/kb_signing_key.pem`. This file is **git-ignored**:
keep it private and back it up. If it is lost, rebuild with a new key. The public key in the app
assets updates automatically.

## Getting DDInter

1. Open https://ddinter.scbdd.com/download/ in a browser.
2. Download all the per-category CSV files (`ddinter_downloads_code_*.csv`).
3. Put them in `kb/sources/ddinter/`. They are git-ignored, but the compiled KB inside the APK zip is committed.
4. Run `python kb/build_kb.py`.

DDInter is licensed CC BY-NC-SA 4.0. That suits this non-commercial academic project. Credit DDInter
in the report and keep the compiled KB under the same licence.

DDInter names drugs by international non-proprietary name, so some of its names differ from local
usage (e.g. *Acetaminophen*). `synonyms.csv` maps these to the curated ingredient
(`acetaminophen → paracetamol`), so both sources merge into one entity.

## Getting RxNorm (optional)

Download the *RxNorm Current Prescribable Content* release (no UMLS licence needed) and copy
`RXNCONSO.RRF` and `RXNREL.RRF` into `kb/sources/rxnorm/`. The build attaches RxCUIs to matching
ingredients and adds US brand names as synonyms.

## Curated supplement table

`sources/supplement_interactions.csv` has these columns: `ingredient_a, ingredient_b, severity,
mechanism, management, evidence_ref, reviewed`. Each `evidence_ref` key is listed in
`sources/REFERENCES.md`. **Rows start as `reviewed=no`.** Check each row against its source, correct
it if needed, and set `reviewed=yes`. Release builds are blocked until every row is reviewed.

## Merge rules

- Ingredients are keyed by a slug of the normalised name (`st-johns-wort`).
- A DDInter drug whose name matches a curated name or synonym merges into the curated ingredient.
  Salt forms listed as synonyms (e.g. `calcium carbonate → calcium`) merge on purpose. This is
  reasonable for a consumer tool and is listed as a limitation.
- If both sources record the same pair, both records are kept. The app shows the worst severity and
  lists every source.
