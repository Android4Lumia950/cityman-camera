# Arco — Roadmap

**Data**: 31 ottobre 2026. **Obiettivo**: la fotocamera di tutti i giorni del Lumia 950 XL con
LineageOS 23.2, con controlli manuali sui quadranti ad arco e una qualità multi-frame vicina a
GCam, entro i limiti termici dell'MSM8994.

Regola: una fase si chiude quando il criterio di uscita è verificato sul telefono e scritto
nel README (o in un report), non quando il codice è su GitHub.

---

## Dove siamo (31/10 sera)

| Area | Stato | Note |
|---|---|---|
| Anteprima, rotazione | funziona, verticale e orizzontale | provato dall'utente |
| Quadranti WB/fuoco/ISO/tempo/EV | funzionano dal vivo | ISO manuale limitata a 342 dal HAL |
| JPEG + DNG | funzionano | DNG anche del RAW fuso |
| Video | funziona su entrambe le camere | via YUV → GL (il gralloc della ROM rifiuta camera→encoder); posteriore 15–20 fps |
| Multi-frame (MF) | funziona, opzionale, anche frontale | riferimento più nitido, giroscopio, notte da fermo, sotto-esposizione adattiva |
| Strumenti | istogramma, zebra, peaking, autoscatto, suono | tutti disattivabili |
| Pubblicazione | GitHub pubblico, GPL-3.0 | release R8 pronta, manca il keystore |

## Fase 1 — Consolidamento

**Perché prima**: tutto il resto si appoggia su un'app che non perde scatti e non si blocca.

- ✅ Video posteriore e frontale con audio (prove brevi); corretto il blocco del gralloc.
  Da fare: 10 minuti di fila, interruzioni (chiamata, schermo spento) durante la registrazione.
- ✅ MF sulla frontale: colori, orientamento, pixel caldi corretti, bordo non attivo ritagliato.
- ✅ Scatti MF in serie: 5 consecutivi senza errori né processi uccisi (uno alla volta).
- ✅ Uscita dall'app durante l'elaborazione: annullamento pulito.
- ✅ EXIF nel JPEG MF.
- ✅ Release con R8 provata sul telefono. Da fare: keystore dell'utente e release su GitHub.

**Uscita**: una giornata d'uso normale senza crash né scatti persi, log puliti.

---

## Fase 2 — Qualità dell'immagine MF

In ordine di resa attesa:

1. ✅ **Fotogramma di riferimento più nitido** tra i primi 3 (il primo risente della pressione
   del tasto), misurato con l'energia del gradiente sul livello grigio.
2. ✅ **Esposizione guidata dal giroscopio**: con mano ferma tempi più lunghi e meno guadagno
   digitale, con mano mossa tempi più corti e più fotogrammi.
3. ½ **Scene in controluce**: fatta la sotto-esposizione adattiva dall'anteprima (0,4–1,6 EV)
   con tone mapping più forte; da fare il bracketing (fotogrammi corti per le alte luci, lunghi per le ombre)
   fuso con exposure fusion, al posto della sola sotto-esposizione fissa di 0,7 EV.
4. **Tone mapping**: sostituire base/dettaglio con una piramide laplaciana locale (meno aloni,
   più profondità controllabile); un'opzione "naturale / vivace".
5. **Colore**: confronto sistematico con il JPEG del HAL (calore, pelle, verdi); profilo
   colore proprio se serve; verifica della mappa di vignettatura sul bordo.
6. **Fusione nel dominio delle frequenze** (Wiener per tile, come HDR+) o kernel robusti
   alla Wronski, per ridurre ancora rumore e sdoppiamenti nelle zone in movimento.
7. ✅ (prima versione) **Modalità notte**: 12–15 fotogrammi, rilevamento cavalletto (giroscopio fermo →
   esposizioni fino a 1 s).
8. ✅ Pixel caldi (filtro sui vicini dello stesso colore). Da fare: dalla differenza tra fotogrammi; nitidezza tarata sulla risposta della lente.

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
- ✅ Controllo termico: con poco margine (`getThermalHeadroom`) il burst scende a 4 fotogrammi.
- Codifica: il telefono non ha un encoder HEVC hardware (niente HEIF veloce); resta la CPU.

**Uscita**: scatto MF da 6 fotogrammi pronto per lo scatto successivo in meno di 2,5 s,
10 scatti di fila senza throttling evidente.

---

## Fase 4 — Funzioni dell'app

- ✅ DNG anche in modalità MF: il RAW fuso.
- ✅ Istogramma, focus peaking, zebra. Da fare: livella.
- Quadranti nel video: i controlli si applicano già anche al video (da verificare a fondo);
  da fare il blocco esposizione.
- ✅ Autoscatto e suono disattivabile. Da fare: scatto a raffica, posizione nell'EXIF.
- Stabilizzazione video elettronica con il giroscopio.
- Animazioni dei quadranti e aspetto rifiniti; icona e schermata impostazioni definitive.

**Uscita**: ogni funzione con una voce nel README e una prova sul telefono.

---

## Fase 5 — Integrazione nella ROM (rimandata)

Rimandata dall'utente il 31/10: per ora si lavora sulle fasi 1–4.

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
