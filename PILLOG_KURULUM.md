# Pil Log — arka plan pil / CPU kaydedici (Android)

Arayüzü olmayan (sadece tek seferlik kurulum ekranı olan) bir uygulama. Kurulduktan sonra
**her ~15 dakikada bir** telefonun durumunu ölçer ve şu dosyaya ekler:

```
İndirilenler/PilLog/pil_log_YYYY-MM-DD.txt   (her gün yeni dosya)
```

## Neler kaydediliyor

Her kayıtta:

- **Pil:** yüzde ve bir önceki kayda göre değişim, şarj durumu, sıcaklık, voltaj, anlık ve ortalama akım (mA), kalan mAh, ekran/Doze/güç tasarrufu/ısıl durum
- **Uygulama ve süreç bazında, o aralıkta (sistem uygulamaları ve çekirdek dahil):**
  tahmini pil tüketimi (mAh), CPU süresi, wakelock (ekran kapalıyken telefonu uyanık tutma) süresi,
  uyandırma alarmları, çalışan arka plan işleri (job), mobil ve WiFi veri kullanımı
- **Ayrıntı (her kalem, sınırsız):** her süreç (surfaceflinger gibi native/sistem daemon'ları dahil) için CPU,
  başlatma, ANR ve çökme; her servis için çalışma süresi ve başlatma sayısı; adıyla her wakelock, uyandırma alarmı,
  iş (job) ve senkronizasyon; kernel wakelock'ları ve cihazı uyandıran donanım nedenleri; sensör kullanımı
- **Çalışan süreçler ve servisler:** her kayıtta başlayan, sonlanan ve durumu değişen süreçler/servisler; saat başı tam liste
- **Olay günlüğü:** UsageStats'ın aralıktaki tüm olayları zaman sırasıyla (uygulama açıldı/kapandı, bildirim geldi,
  ön plan servisi başladı/durdu, ekran açıldı/kapandı, kilit, bekleme kovası değişti vb.)
- **Son tam şarjdan beri en çok tüketen 15 kalem** ve ekran, hücresel, WiFi, boşta gibi donanım kalemleri
- **Ne yapıldı:** hangi uygulama kaç dakika ekranda kaldı, kaç kez açıldı, ne kadar ön plan servisi
  (müzik, navigasyon, indirme vb.) çalıştırdı; ekranın kaç kez açıldığı ve açık kalma süresi
- **Pil Log'un kendi tüketimi:** ölçümün kaç ms sürdüğü ve sistemin Pil Log için hesapladığı mAh/CPU.
  Böylece sonuçların bu uygulama yüzünden bozulmadığını dosyanın içinden doğrulayabilirsiniz.

## Neden az pil harcıyor

- Sürekli çalışan servis, bildirim veya wakelock **yok**. Android'in `JobScheduler`'ı ile periyodik iş;
  sistem bunu kendi uyanma anlarıyla (Doze bakım pencereleri) birleştirdiği için telefonu ayrıca uyandırmaz.
- Her tur sadece birkaç yüz ms sürer; veriyi sistemin zaten tuttuğu istatistiklerden okur, kendisi ölçüm yapmak için CPU harcamaz.
- Kütüphane yok, saf Java. APK çok küçük.

## Kurulum

1. **APK'yı indirin:** Bu deponun GitHub sayfasında **Releases → "Pil Log (son sürüm)" → `PilLog.apk`**
   (ya da Actions sekmesinde son "APK derle" çalışmasının çıktısı). Telefonda açıp kurun
   ("bilinmeyen kaynaklar" izni isteyebilir).
2. Uygulamayı **bir kez açın**. Açılan ekranda:
   - **"Kullanım erişimi iznini aç"** → Pil Log'u açın.
   - **"Pil optimizasyonundan muaf tut"** → İzin verin. (Bunu yapmazsanız ve uygulamayı hiç açmazsanız Android
     kayıtları günde bire kadar seyreltebilir. Muafiyet uygulamanın tüketimini artırmaz; sadece 15 dakikalık aralık korunur.)
3. **DUMP izni (bir kerelik):** Tüm uygulamaların ve sistem süreçlerinin mAh/CPU verisi için gerekli.
   Android normal uygulamaların başka uygulamaların pil ve işlemci tüketimini görmesine izin vermez. Bu iznin
   tek yolu ADB'dir, root gerekmez:

   ```
   adb shell pm grant com.eymen.pillog android.permission.DUMP
   adb shell pm grant com.eymen.pillog android.permission.BATTERY_STATS
   ```

   (Asıl gerekli olan ilk satırdır; ikinci satır bazı cihazlarda hata verebilir, önemsemeyin.)

   - **Bilgisayarla:** Geliştirici seçenekleri → USB hata ayıklama açık, telefonu bağlayıp yukarıdaki komutları çalıştırın.
   - **Bilgisayarsız (Android 11+):** Geliştirici seçenekleri → *Kablosuz hata ayıklama* ve telefonda bir ADB
     uygulaması (ör. LADB, Shizuku + terminal) ile aynı komutlar.

   İzin bir kez verilir, yeniden başlatmada kaybolmaz. (Uygulamayı kaldırıp yeniden kurarsanız tekrar vermeniz gerekir.)
4. Bitti. Ekranı kapatabilirsiniz; kayıt arka planda devam eder, telefon yeniden başlasa da sürer.

DUMP izni olmadan da çalışır: pil durumu, akım ve "hangi uygulama ne kadar ekranda / ön plan servisinde" kaydedilir,
ama uygulama bazında mAh ve CPU sütunları olmaz. Bu durum log dosyasında da yazar.

## Örnek kayıt

```
==================== 2026-11-25 19:30:00 ====================
Aralık: 19:15:02 -> 19:30:00 (14 dk 58 sn)
PİL: %78 (önceki ölçüme göre -2 puan) | Durum: Deşarj | Şarj kaynağı: yok | Sağlık: İyi | Sıcaklık: 31.2°C | Voltaj: 3912 mV
Akım: anlık -312 mA, ortalama -280 mA | Kalan şarj: 3120 mAh | Ekran şu an: kapalı | Doze: hayır | ...
Ekran açık (aralıkta): 6 dk 10 sn | ekran açılma sayısı: 3

--- UYGULAMA/SÜREÇ BAZINDA, BU ARALIKTA (sistem dahil) ---
     mAh   CPU(sn)  WakeLock  Alarm    İş  Mobil(KB)   WiFi(KB)  Uygulama
   21.40     88.3       12s      0     1      14200          0  YouTube (com.google.android.youtube) [SİSTEM]
    4.10     20.1     1m40s      3     4        120          0  Google Play hizmetleri (com.google.android.gms) [SİSTEM]
    2.05     15.7        2s      1     0          0          0  Android Sistemi (system_server) [SİSTEM uid 1000]
    ...
Pil Log'un kendisi: bu ölçüm 310 ms sürdü, 95 ms CPU | sistem ölçümüne göre aralıkta 0.004 mAh, 0.12 sn CPU
```

## Samsung (One UI) için ek ayar — önemli

One UI, pil optimizasyonu muafiyetine ek olarak uygulamaları kendi listeleriyle "uyutur". Kayıtlar düzenli gelsin diye:

1. **Ayarlar → Uygulamalar → Pil Log → Pil → "Kısıtlamasız"** seçin.
2. **Ayarlar → Pil → Arka plan kullanım sınırları:** Pil Log "Uyuyan uygulamalar" veya "Derin uyuyan uygulamalar"
   listesindeyse çıkarın. İsterseniz "Hiç uyumayan uygulamalar"a ekleyin.
3. "Kullanılmayan uygulamaları uyku moduna al" açıksa Pil Log'u yukarıdaki gibi istisna yapın.

Bunlar uygulamanın daha fazla pil harcamasına yol açmaz; sadece Samsung'un 15 dakikalık işi iptal etmesini önler.

## Notlar

- mAh değerleri Android'in kendi güç modeline göre **tahmindir**. Ayarlar → Pil ekranındaki rakamlarla aynı kaynaktan gelir.
- İlk kayıt referans alır; aralık farkları ikinci kayıttan itibaren yazılır. Tam şarj sonrası sistem sayaçları sıfırlanınca bu otomatik algılanır.
- Bazı üreticiler (Xiaomi, Huawei, Oppo vb.) arka plan işlerini ek olarak kısıtlar. Kayıtlar seyrekse
  o cihazın "Otomatik başlatma" / "Arka plan etkinliği" ayarından Pil Log'a izin verin.
- Kaynak kod: `app/src/main/java/com/eymen/pillog/`
