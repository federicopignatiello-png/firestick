# FirePad / 01 — trackpad Bluetooth per Fire TV

Prototipo Android 9+ (API 28). Il telefono si registra come mouse HID e può cercare i dispositivi vicini in BLE. Non serve un'app sulla Fire TV.

## Perché la ricerca può restare vuota

La Fire Stick, mentre navighi, di solito **non si fa trovare**: è lei che cerca il telecomando. Le versioni precedenti di FirePad non avviavano alcuna scansione BLE e mostravano solo i dispositivi già associati, quindi l'elenco restava vuoto.

Flusso che funziona con l'HID di Android:

1. Apri FirePad e concedi i permessi (su Android 12+: dispositivi nelle vicinanze; su Android 9–11: posizione, che deve anche essere **accesa**).
2. Accetta la richiesta che rende il telefono visibile per qualche minuto.
3. Leggi in app il **nome Bluetooth del telefono**.
4. Sulla Fire Stick: Impostazioni > Controller e dispositivi Bluetooth > Altri dispositivi Bluetooth > Aggiungi dispositivo Bluetooth, poi scegli quel nome.
5. Quando FirePad dice `Connesso`, usa il trackpad. Un tap è il clic sinistro.

In più l'app esegue una ricerca BLE senza filtro UUID e, dopo, una ricerca Bluetooth classica. Se la Fire Stick sta davvero annunciando il proprio nome, compare in cima quando il nome contiene "Fire" o "Amazon". Gli altri dispositivi restano in lista: non vengono nascosti da un filtro.

## Aprire e compilare

1. Apri la radice di questo repository con Android Studio.
2. Lascia sincronizzare Gradle (AGP 8.7.3, compileSdk 35, Java 17).
3. Collega un telefono con debug USB e premi Run, oppure genera l'APK debug.
4. In alternativa, scarica l'artefatto **FirePad-debug-APK** dal workflow GitHub Actions. Vedi `BUILD-ONLINE.md`.

L'APK debug è `app/build/outputs/apk/debug/app-debug.apk`.

## Limiti

- Alcuni telefoni non implementano il ruolo HID Device. In quel caso la ricerca può funzionare, ma il trackpad no: non è un permesso mancante.
- Fire OS può ignorare un telefono che in inquiry risulta ancora come telefono e non come periferica di puntamento. Android non offre un'API pubblica per impostare il campo BLE Appearance `0x03C2` (mouse).
- L'app va tenuta in primo piano: Android può togliere la registrazione HID in background.
- La compatibilità dipende dal telefono e dal modello di Fire Stick. Il cursore in Brave può comunque non comparire.
- Solo mouse (spostamento, tap, clic destro, scroll). Non ci sono tasti Home/Back.

## Specifiche

Java 17 · Android Gradle Plugin 8.7.3 · compileSdk 35 · minSdk 28. `BluetoothHidDevice`, report HID da 4 byte (pulsanti, dx, dy, rotella), ID 1. La ricerca BLE non usa i risultati per la posizione (`neverForLocation` su Android 12+).
