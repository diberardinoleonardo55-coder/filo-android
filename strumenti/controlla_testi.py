# -*- coding: utf-8 -*-
"""Controlla le traduzioni dell'interfaccia.

    python strumenti/controlla_testi.py

Il compilatore non puo' accorgersi di una chiave scritta male: `Testi.t("...")`
accetta qualsiasi stringa e, se non la trova, restituisce la chiave stessa.
Il difetto si vedrebbe solo passando all'italiano e leggendo una frase inglese
in mezzo alle altre.

Vengono quindi confrontate le chiavi usate nei sorgenti con quelle presenti nel
dizionario, e i segnaposto fra chiave e traduzione.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

RADICE = Path(__file__).resolve().parent.parent
SORGENTI = RADICE / "app/src/main/java/it/leo/filo"
TESTI = SORGENTI / "Testi.kt"

CHIAMATA = re.compile(r'\bt\(\s*("(?:[^"\\]|\\.)*"(?:\s*\+\s*t\(\s*"(?:[^"\\]|\\.)*")*)')
STRINGA = re.compile(r'"((?:[^"\\]|\\.)*)"')
VOCE = re.compile(r'^\s*("(?:[^"\\]|\\.)*")\s*(?:\n\s*)?to\s*("(?:[^"\\]|\\.)*")', re.M)
SEGNAPOSTO = re.compile(r"\{(\w+)\}")

guai: list[str] = []

# --- il dizionario -----------------------------------------------------------

testo_dizionario = TESTI.read_text(encoding="utf-8")
# le voci possono stare su una riga o su due, con "to" a capo
grezze = re.findall(
    r'"((?:[^"\\]|\\.)*)"\s*\n?\s*to\s*\n?\s*"((?:[^"\\]|\\.)*)"', testo_dizionario
)
dizionario = {chiave: valore for chiave, valore in grezze}
if len(dizionario) < 20:
    guai.append(f"il dizionario sembra vuoto: solo {len(dizionario)} voci lette")

# Una traduzione identica alla chiave inglese di solito e' una traduzione
# perduta: compare in inglese in mezzo alle altre e nessun altro controllo se
# ne accorge. Restano fuori le frasi fatte di soli simboli e segnaposto.
UGUALI_PER_DAVVERO = {"en", "it", "computer"}

for chiave, valore in dizionario.items():
    if set(SEGNAPOSTO.findall(chiave)) != set(SEGNAPOSTO.findall(valore)):
        guai.append(f"segnaposto diversi: {chiave!r} -> {valore!r}")
    if chiave == valore and chiave not in UGUALI_PER_DAVVERO:
        senza = SEGNAPOSTO.sub("", chiave).strip()
        # se tolti i segnaposto non restano lettere, e' una frase di soli simboli
        if any(c.isalpha() for c in senza):
            guai.append(f"traduzione rimasta in inglese: {chiave!r}")

# --- le chiavi usate nei sorgenti --------------------------------------------

usate: dict[str, str] = {}
for f in sorted(SORGENTI.glob("*.kt")):
    if f.name == "Testi.kt":
        continue
    for riga in f.read_text(encoding="utf-8").split("\n"):
        for pezzo in re.findall(r'\bt\(\s*"((?:[^"\\]|\\.)*)"', riga):
            usate[pezzo] = f.name

senza_traduzione = sorted(k for k in usate if k not in dizionario)
if senza_traduzione:
    for k in senza_traduzione:
        guai.append(f"chiave senza traduzione italiana ({usate[k]}): {k!r}")

# le lingue disponibili devono essere quelle dichiarate
if '"en" to "English"' not in testo_dizionario or '"it" to "Italiano"' not in testo_dizionario:
    guai.append("l'elenco delle lingue non e' quello atteso")

# --- esito -------------------------------------------------------------------

print(f"chiavi usate nei sorgenti: {len(usate)}")
print(f"voci nel dizionario italiano: {len(dizionario)}")
if guai:
    print()
    for g in guai:
        print("  -", g)
    sys.exit(1)
print("\nTESTI OK")
