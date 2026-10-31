# Arco — Roadmap

**Data**: 31 ottobre 2026. **Obiettivo**: la fotocamera di tutti i giorni del Lumia 950 XL con
LineageOS 23.2, con controlli manuali sui quadranti ad arco e una qualità multi-frame vicina a
GCam, entro i limiti termici dell'MSM8994.

Regola: una fase si chiude quando il criterio di uscita è verificato sul telefono e scritto
nel README (o in un report), non quando il codice è su GitHub.

---

## Dove siamo (31/10, fine giornata)

| Area | Stato | Note |
|---|---|---|
| Anteprima, rotazione, quadranti | funzionano | ISO manuale limitata a 342 dal HAL |
| JPEG + DNG | funzionano | anche il DNG del RAW fuso |
| Video | funziona su entrambe le camere | YUV → GL; blocco AE, time-lapse; stabilizzazione sperimentale non provata in movimento |
| Multi-frame | funziona, opzionale | riferimento nitido, giroscopio + accelerometro, notte da fermo, zoom, JPEG in ~0,2 s |
| Bracketing controluce, tone mapping a due scale | compilati | **da provare sul telefono** |
| Avvio da schermata di blocco | funziona | va scelta Arco come predefinita una volta |
| Impostazioni | pannello rapido + schermata completa, accento Material You | |
| Test automatici | 7 test JVM | `./gradlew testDebugUnitTest` |

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
3. ✅ (da provare) **Scene in controluce**: sotto-esposizione adattiva dall'anteprima e 2
   fotogrammi a −2 EV in coda al burst, usati solo dove il riferimento è saturo (fotogrammi corti per le alte luci, lunghi per le ombre)
   fuso con exposure fusion, al posto della sola sotto-esposizione fissa di 0,7 EV.
4. ½ **Tone mapping**: fatte due scale (fine vicino ai bordi, per meno aloni); da fare una vera
   piramide laplaciana locale (meno aloni,
   più profondità controllabile); un'opzione "naturale / vivace".
5. **Colore** (da fare con luce del giorno e scene ferme): confronto sistematico con il JPEG del HAL (calore, pelle, verdi); profilo
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

- **Zero shutter lag** (studiato, non fatto): il RAW nelle richieste continue funziona senza
  errori (prova del 31/10, di sera l'anteprima va comunque a 9–12 fps); manca una fusione che
  normalizzi fotogrammi con esposizioni diverse (anteprima contro piano del burst).
- ✅ **Codifica JPEG più rapida**: NV21 dalla GPU, 4 strisce in parallelo con l'encoder YUV di
  sistema unite con i restart marker: da 1,5 s a circa 0,2 s.
- Shader di fusione: evitare gli array locali (spill dei registri sull'Adreno 430), calcoli
  in fp16 dove basta, meno passaggi nella rifinitura.
- ✅ Controllo termico: con poco margine (`getThermalHeadroom`) il burst scende a 4 fotogrammi.
- Codifica: il telefono non ha un encoder HEVC hardware (niente HEIF veloce); resta la CPU.

**Uscita**: scatto MF da 6 fotogrammi pronto per lo scatto successivo in meno di 2,5 s,
10 scatti di fila senza throttling evidente.

---

## Fase 4 — Funzioni dell'app

- ✅ DNG anche in modalità MF: il RAW fuso.
- ✅ Zoom: tasti 1×/2×/4× sopra lo scatto; il multi-frame ritaglia e raddoppia (bicubico). Da
  fare: vera super-risoluzione multi-fotogramma (kernel alla Wronski).
- ✅ Avvio dalla schermata di blocco, camera e modalità ricordate, livella.
- ✅ Impostazioni a schermo intero con sezioni scorrevoli, pannello rapido, accento Material You.
- ✅ Istogramma, focus peaking, zebra. Da fare: livella.
- ✅ Quadranti nel video verificati (ISO e tempo manuali applicati); ✅ blocco AE/AWB;
  ✅ time-lapse; stabilizzazione elettronica sperimentale (da provare in movimento).
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
