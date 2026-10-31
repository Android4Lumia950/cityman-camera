# Arco — Roadmap

**Data**: 31 ottobre 2026. **Obiettivo**: la fotocamera di tutti i giorni del Lumia 950 XL con
LineageOS 23.2, con controlli manuali sui quadranti ad arco e una qualità multi-frame vicina a
GCam, entro i limiti termici dell'MSM8994.

Regola: una fase si chiude quando il criterio di uscita è verificato sul telefono e scritto
nel README (o in un report), non quando il codice è su GitHub.

---

## Dove siamo (31/10, commit `45c464f`)

| Area | Stato | Note |
|---|---|---|
| Anteprima, rotazione | funziona, verticale e orizzontale | provato dall'utente |
| Quadranti WB/fuoco/ISO/tempo/EV | funzionano dal vivo | ISO manuale limitata a 342 dal HAL |
| JPEG + DNG | funzionano (posteriore) | DNG verificato con dcraw |
| Video | implementato | **non provato** da me; audio, frontale e durata lunga da verificare |
| Multi-frame (MF) | funziona, opzionale | 6–8 RAW, ~0,3 s/fotogramma + 1,3 s di rifinitura, JPEG 20 MP |
| Frontale | anteprima e foto | MF sulla frontale **non verificato** |
| Pubblicazione | GitHub pubblico, GPL-3.0 | build debug, nessuna release firmata |

---

## Fase 1 — Consolidamento

**Perché prima**: tutto il resto si appoggia su un'app che non perde scatti e non si blocca.

- Video: posteriore e frontale, con audio, 10 minuti di fila; controllare temperatura e file
  finale. Interruzioni (chiamata, schermo spento, cambio app) durante la registrazione.
- MF sulla frontale (1304×972, pattern GRBG): colori, orientamento, specchiatura.
- Raffica di 10 scatti MF consecutivi: memoria (nessun OOM), tempi, temperatura.
- Ciclo di vita: uscire dall'app durante l'elaborazione MF, chiamata in arrivo, permessi
  revocati, rotazione durante lo scatto.
- EXIF nel JPEG MF (oggi assente): ISO, tempo, data, modello, orientamento già applicato.
- Release firmata con R8, numero di versione, changelog; APK allegato a una release GitHub.

**Uscita**: una giornata d'uso normale senza crash né scatti persi, log puliti.

---

## Fase 2 — Qualità dell'immagine MF

In ordine di resa attesa:

1. **Fotogramma di riferimento più nitido** tra i primi 3 (il primo risente della pressione
   del tasto), misurato con l'energia del gradiente sul livello grigio.
2. **Esposizione guidata dal giroscopio**: con mano ferma tempi più lunghi e meno guadagno
   digitale, con mano mossa tempi più corti e più fotogrammi.
3. **Scene in controluce**: bracketing (fotogrammi corti per le alte luci, lunghi per le ombre)
   fuso con exposure fusion, al posto della sola sotto-esposizione fissa di 0,7 EV.
4. **Tone mapping**: sostituire base/dettaglio con una piramide laplaciana locale (meno aloni,
   più profondità controllabile); un'opzione "naturale / vivace".
5. **Colore**: confronto sistematico con il JPEG del HAL (calore, pelle, verdi); profilo
   colore proprio se serve; verifica della mappa di vignettatura sul bordo.
6. **Fusione nel dominio delle frequenze** (Wiener per tile, come HDR+) o kernel robusti
   alla Wronski, per ridurre ancora rumore e sdoppiamenti nelle zone in movimento.
7. **Modalità notte**: 12–15 fotogrammi, rilevamento cavalletto (giroscopio fermo →
   esposizioni fino a 1 s).
8. Pixel caldi dalla differenza tra fotogrammi; nitidezza tarata sulla risposta della lente.

**Uscita**: su 5 scene fisse (luce piena, interno, controluce, notte, ritratto) il risultato MF
è migliore del JPEG del HAL in ognuna, confronto salvato nel repository.

---

## Fase 3 — Prestazioni

Oggi (6 fotogrammi): circa 2 s di fusione sovrapposta alla cattura, 1 s di rifinitura GPU,
0,3 s di lettura, 0,8–1,4 s di codifica JPEG in background.

- **Zero shutter lag**: buffer circolare di 2–3 RAW prima dello scatto (≈ 40 MB ciascuno),
  così il fotogramma di riferimento è quello del momento della pressione.
- **Codifica JPEG più rapida**: libjpeg-turbo via NDK su più core (strisce con restart
  marker), oppure l'encoder hardware tramite il reprocessing YUV del HAL (limite 4160×3120 da
  valutare).
- Shader di fusione: evitare gli array locali (spill dei registri sull'Adreno 430), calcoli
  in fp16 dove basta, meno passaggi nella rifinitura.
- Controllo termico: leggere la temperatura e ridurre i fotogrammi quando sale.

**Uscita**: scatto MF da 6 fotogrammi pronto per lo scatto successivo in meno di 2,5 s,
10 scatti di fila senza throttling evidente.

---

## Fase 4 — Funzioni dell'app

- DNG anche in modalità MF: il RAW fuso (più pulito del singolo fotogramma).
- Strumenti "pro": istogramma, focus peaking, zebra sulle alte luci, livella.
- Quadranti anche nel video (tempo, ISO, WB, fuoco manuale) e blocco esposizione.
- Autoscatto, scatto a raffica, suono dello scatto disattivabile, posizione nell'EXIF.
- Stabilizzazione video elettronica con il giroscopio.
- Animazioni dei quadranti e aspetto rifiniti; icona e schermata impostazioni definitive.

**Uscita**: ogni funzione con una voce nel README e una prova sul telefono.

---

## Fase 5 — Integrazione nella ROM

- Arco nel device tree cityman come app di sistema (prebuilt firmato o compilata da sorgente),
  scelta dell'utente se affiancarla ad Aperture o sostituirla come predefinita.
- Dal lato HAL, se possibile: profilo di rumore nei metadati statici (dal modello misurato
  dall'app), modalità di anteprima binned a 30 fps, ISO manuale oltre 342.
- Note per chi usa altri Lumia (talkman): cosa cambia tra 950 e 950 XL.

**Uscita**: ROM con Arco installata di serie, avviata da tasto fotocamera e schermata di blocco.

---

## Fuori programma

Living Images e Movie Moments (scartati dall'utente il 31/10). Port di GCam: non funziona su
questo HAL e richiede i servizi Google.
