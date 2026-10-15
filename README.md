# Midterms Dagelijks

Een dagelijkse Nederlandstalige podcast van 5 minuten over de Amerikaanse tussentijdse verkiezingen, met een eigen Android-app.

## Hoe het werkt

1. **Elke ochtend rond 06:50** schrijft een geplande Claude-taak een script op basis van het laatste nieuws en zet het in `episodes/JJJJ-MM-DD.txt`.
2. **GitHub Action "Aflevering publiceren"** stuurt het script naar ElevenLabs (model `eleven_flash_v2_5`, de goedkoopste), zet de MP3 in `docs/audio/` en werkt `docs/episodes.json` bij.
3. **GitHub Pages** publiceert `docs/` op `https://jasperbrouwers1991-ai.github.io/Midterms-podcast/`.
4. **De Android-app** haalt `episodes.json` op en speelt de afleveringen af, ook op de achtergrond en vanaf het vergrendelscherm.

Lukt ElevenLabs niet (geen sleutel, credits op), dan wordt de aflevering toch gepubliceerd als tekst en leest de app hem voor met de stem van je telefoon.

## Eenmalig instellen

1. **Repo openbaar maken** (nodig voor gratis GitHub Pages): Settings → General → Danger Zone → Change visibility → Public.
2. **Pages aanzetten**: Settings → Pages → Source: *Deploy from a branch* → Branch: `main`, map `/docs` → Save.
3. **ElevenLabs-sleutel toevoegen**: maak op elevenlabs.io een API-sleutel (Profiel → API Keys, met recht op *Text to Speech* en *Voices: read*). Daarna in GitHub: Settings → Secrets and variables → Actions → *New repository secret*, naam `ELEVENLABS_API_KEY`.
4. **Optioneel, een vaste stem**: onder dezelfde pagina, tabblad *Variables*, voeg `ELEVENLABS_VOICE_ID` toe. Zonder deze kiest het script de eerste standaardstem van je account.
5. **Eerste aflevering maken**: Actions → *Aflevering publiceren* → *Run workflow*.

## App installeren

Ga op je Android-telefoon naar **Releases → Midterms Dagelijks (nieuwste app)** en download `MidtermsDagelijks.apk`. Open het bestand en sta installatie uit deze bron toe. Nieuwe versies installeren gewoon over de oude heen.

## Kosten

- GitHub (Actions, Pages, Releases): gratis voor een openbare repo.
- ElevenLabs: een aflevering is ongeveer 4.800 tekens, met het Flash-model ongeveer 2.400 credits. Het gratis plan (10.000 credits per maand) is genoeg voor zo'n 4 afleveringen.
