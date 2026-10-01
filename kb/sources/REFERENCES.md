# Evidence references for `supplement_interactions.csv`

Every row in `supplement_interactions.csv` carries an `evidence_ref` key from this file and a
`reviewed` flag. **All rows are drafts (`reviewed=no`) until checked against the cited source.**
Rows marked `REVIEW` still need a primary source. `build_kb.py` prints a warning for every
unreviewed row, and the build fails with `--release` until none remain.

| Key | Reference |
|---|---|
| Bailey2013 | Bailey, D. G., Dresser, G., & Arnold, J. M. O. (2013). Grapefruit–medication interactions: Forbidden fruit or avoidable consequences? *CMAJ, 185*(4), 309–316. https://doi.org/10.1503/cmaj.120951 |
| Campbell1991 | Campbell, N. R., & Hasinoff, B. B. (1991). Iron supplements: A common cause of drug interactions. *British Journal of Clinical Pharmacology, 31*(3), 251–255. |
| Hartter2000 | Härtter, S., Grözinger, M., Weigmann, H., Röschke, J., & Hiemke, C. (2000). Increased bioavailability of oral melatonin after fluvoxamine coadministration. *Clinical Pharmacology & Therapeutics, 67*(1), 1–6. |
| Henderson2002 | Henderson, L., Yue, Q. Y., Bergquist, C., Gerden, B., & Arlett, P. (2002). St John's wort (*Hypericum perforatum*): Drug interactions and clinical outcomes. *British Journal of Clinical Pharmacology, 54*(4), 349–356. |
| Izzo2009 | Izzo, A. A., & Ernst, E. (2009). Interactions between herbal medicines and prescribed drugs: An updated systematic review. *Drugs, 69*(13), 1777–1798. |
| Medsafe-DS | Medsafe. (n.d.). *Medicine data sheets* (data sheet for the named medicine, interactions section). New Zealand Medicines and Medical Devices Safety Authority. https://www.medsafe.govt.nz/ |
| NCCIH-RYR | National Center for Complementary and Integrative Health. (n.d.). *Red yeast rice*. https://www.nccih.nih.gov/health/red-yeast-rice |
| Singh2000 | Singh, N., Singh, P. N., & Hershman, J. M. (2000). Effect of calcium carbonate on the absorption of levothyroxine. *JAMA, 283*(21), 2822–2825. |
| Yuan2004 | Yuan, C.-S., Wei, G., Dey, L., et al. (2004). Brief communication: American ginseng reduces warfarin's effect in healthy patients: A randomized, controlled trial. *Annals of Internal Medicine, 141*(1), 23–27. |
| REVIEW | No source yet. Find a primary source or drop the row before release. |

## Drug–drug interactions

Drug–drug pairs come from **DDInter** (Xiong et al., 2022; Tian et al., 2025), licensed
CC BY-NC-SA 4.0. That licence lets this academic, non-commercial project redistribute the compiled
data, as long as the source is credited and derivatives use the same licence. See `kb/README.md`.

`dev_fixture_ddi.csv` is a **development fixture only**. It holds a few well-known pairs so the app
works before DDInter is downloaded. It is labelled `DEV-FIXTURE` in the app's provenance line and
must not be used for evaluation.
