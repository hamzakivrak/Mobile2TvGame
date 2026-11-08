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
