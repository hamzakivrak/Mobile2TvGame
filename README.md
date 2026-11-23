# Mobile2TvGame

Telefondaki oyunu sanal ekranda calistirip TV'ye aktarma projesi. **1. asama:** Shizuku ile scrcpy-server'i telefonun icinden baslatip video akisinin geldigini dogrulayan test uygulamasi.

## Kurulum

1. Bu klasordeki tum dosyalari GitHub'daki `Mobile2TvGame` deposuna yukleyin (`.github` klasoru dahil).
2. PC'nizdeki `D:\scrcpy-win64-v5.0.1\scrcpy-server` dosyasini kopyalayip depoda su yola **`scrcpy-server.jar`** adiyla yukleyin:
   `app/src/main/assets/scrcpy-server.jar`
   (surum eslesmesi icin PC'deki ayni dosya kullanilir.)
3. GitHub'da **Actions** sekmesinde "Build APK" calisinca, calismanin altindaki **Artifacts** bolumunden `Mobile2TvGame-debug` zip'ini indirin, icinden APK'yi cikarip telefona kurun.
4. Telefona **Shizuku** kurun, acin ve "Kablosuz hata ayiklama ile baslat" adimini izleyin (Wi-Fi gerekir).
5. Mobile2TvGame'i acin: 1) Shizuku izni/baglan, 2) Testi baslat. Ekrandaki ciktiyi bana yazin.

## Notlar
- Surum alani (varsayilan 5.0.1) jar'in surumuyle birebir ayni olmali.
- Baslik cozumleme scrcpy 3.x protokolune gore yazildi; 5.x'te farkliysa ham hex ciktisina bakariz.

## Akicilik ayarlari (akici-yayin dali)
- Telefon: TV'ye video gonderimi artik ayri bir kuyruktan yapilir. TV/Wi-Fi yetismezse kuyruk dolar, bir sonraki keyframe'e kadar P-kareler atilir (gecikme birikmez).
  Ayarlar `RelayMain.kt` basinda: `TV_MAX_QUEUED_BYTES` (kucuk = daha dusuk gecikme, daha sik kisa donma), `TV_TRIM_BYTES`, `TV_SEND_BUFFER`.
- Relay logunda `[tv] kuyruk doldu` mesaji ve `[akis] ... tv-kuyruk=..KB atilan-kare=..` satiri aginizin ne kadar yetersiz kaldigini gosterir.
- TV: decoder kare dusurunce bozuk goruntu yerine keyframe bekler; ses birikirse 200 ms ustu atilir; Wi-Fi dusuk gecikme kilidi ve minimal post-processing acilir.
- Telefon ve TV: Wi-Fi dusuk gecikme kilidi (`WIFI_MODE_FULL_LOW_LATENCY`).
- Derleme: Actions sekmesinde "Build APK" -> Run workflow -> Branch: `akici-yayin`.
