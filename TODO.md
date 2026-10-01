# TODO: Upgrade Two Way menjadi Intercom / Telepon Tanpa Data (WiFi/LAN)

Tujuan: aplikasi yang bisa memanggil dan bicara (suara, opsional video) antar perangkat
yang memakai aplikasi ini lewat WiFi/LAN saja, tanpa kuota data dan tanpa internet/server.

Legenda prioritas: **P0** wajib (tanpa ini tidak jalan di Android modern), **P1** inti fitur, **P2** peningkatan.

---

## Fase 0 - Fondasi build dan kompatibilitas (P0)

- [ ] Migrasi ke AndroidX, hapus `support-v7:21` dan `ActionBarActivity` (ganti `AppCompatActivity`).
- [ ] Update Gradle, AGP, `compileSdk`/`targetSdk` ke versi terbaru, `minSdk` sekitar 24.
- [ ] Hapus folder `.idea/` dari repo dan tambah `.gitignore`.
- [ ] Runtime permission: `RECORD_AUDIO`, `CAMERA`, `POST_NOTIFICATIONS` (Android 13+), `NEARBY_WIFI_DEVICES` (Android 13+).
- [ ] Hapus permission usang/berbahaya: `WRITE_EXTERNAL_STORAGE`, `DISABLE_KEYGUARD` (ganti dengan `showWhenLocked`/`turnScreenOn`).
- [ ] Hapus `getDeviceId()` di `Utilities` (pakai `TelephonyManager`, tidak boleh di Android 10+). Pakai UUID yang dibuat sendiri dan disimpan.
- [ ] Ganti `MainActivity` yang dikunci landscape dengan layout responsif (portrait + landscape).
- [ ] Ganti `ProfileSender`/foto profil yang memakai `MediaStore.Images.Media.DATA` (hilang di scoped storage) dengan `ActivityResultContracts.TakePicture` + `FileProvider`.

## Fase 1 - Service latar belakang yang bisa menerima panggilan (P0)

- [ ] `ListenerService` jadi foreground service dengan `foregroundServiceType` yang benar (`microphone`, `camera`, `connectedDevice` saat dibutuhkan) dan channel notifikasi.
- [ ] Ganti `startActivity()` dari service (diblokir Android 10+) dengan **notifikasi full-screen intent** (`setFullScreenIntent`) untuk panggilan masuk, plus `setShowWhenLocked(true)` / `setTurnScreenOn(true)` di activity.
- [ ] Minta pengecualian battery optimization dan petunjuk per vendor (Xiaomi/Oppo/Samsung) supaya service tidak dimatikan.
- [ ] Jaga WiFi tetap aktif saat layar mati: `WifiManager.WifiLock` (high-perf / low-latency) dan `MulticastLock`.
- [ ] Opsi "mulai saat boot" yang bisa dimatikan user (`BOOT_COMPLETED`, perhatikan pembatasan start service dari background).
- [ ] Hapus kode yang `stopService` di `onDestroy` activity dan perbaiki NPE `listenerService` null.

## Fase 2 - Discovery perangkat (P1)

- [ ] Ganti broadcast UDP buatan sendiri dengan **NSD / mDNS** (`NsdManager`, service type `_twoway._tcp`) yang membawa nama, ID, dan port.
- [ ] Simpan fallback UDP multicast/broadcast untuk jaringan yang memblokir mDNS.
- [ ] Perbaiki bug di `NetworkDiscovery`: socket dibuat ulang tiap loop, `ipList` bocor dan tidak thread-safe, tidak ada heartbeat/expire, `socket.isClosed()` saat `socket` null.
- [ ] Heartbeat berkala dan tandai perangkat online/offline (hapus yang tidak respons beberapa detik).
- [ ] Tambah input IP manual dan QR code pairing sebagai cadangan.
- [ ] Dukung beberapa subnet / VLAN (opsional): input subnet atau daftar IP tetap.
- [ ] Tampilkan nama + foto profil di daftar (sekarang masih IP), tombol panggil per baris.

## Fase 3 - Signaling panggilan (P1)

Ganti handshake 1 byte (`connectionStage`) dengan protokol pesan yang jelas (JSON/protobuf di atas TCP):

- [ ] Pesan: `INVITE`, `RINGING`, `ACCEPT`, `REJECT`, `BUSY`, `CANCEL`, `HANGUP`, `PING`.
- [ ] State machine panggilan: Idle, Calling, Ringing, InCall, Ended (satu sumber kebenaran di service, bukan di `MainActivity`).
- [ ] UI panggilan masuk: Terima / Tolak, ringtone dan getar, timeout otomatis (30 dtk).
- [ ] Tangani panggilan bersamaan (busy), putus koneksi mendadak (timeout heartbeat), dan WiFi putus.
- [ ] **Mode intercom**: opsi auto-answer (suara langsung aktif tanpa diterima) untuk perangkat tertentu yang dipercaya.
- [ ] Riwayat panggilan lokal (Room/SQLite): masuk, keluar, tak terjawab.

## Fase 4 - Audio berkualitas telepon (P1)

- [ ] Pakai `AudioSource.VOICE_COMMUNICATION` + `AudioManager.MODE_IN_COMMUNICATION` untuk echo cancellation, noise suppression, dan AGC bawaan.
- [ ] Pisahkan transport audio dari video: **UDP/RTP** untuk audio (latensi rendah), bukan TCP yang digabung dengan frame JPEG.
- [ ] Codec **Opus** (16 kHz atau lebih, mono, 20 ms frame) menggantikan PCM 8 kHz mentah.
- [ ] Jitter buffer dan penanganan packet loss (PLC dari Opus).
- [ ] Full-duplex (bicara bersamaan) sebagai mode default; push-to-talk jadi opsi mode intercom.
- [ ] Kontrol: mute, speaker/earpiece, volume; dukung headset Bluetooth.
- [ ] Hapus `Thread.sleep(100)` dan buffer `ByteArrayOutputStream` bersama yang tidak thread-safe di `Audio`.

## Fase 5 - Video (P2)

- [ ] Ganti `android.hardware.Camera` (deprecated) dengan **CameraX**.
- [ ] Ganti MJPEG per-frame dengan **H.264 via `MediaCodec`** (hardware encoder) lewat RTP/UDP.
- [ ] Adaptasi bitrate/resolusi berdasarkan kualitas jaringan; pilih kamera depan/belakang.
- [ ] Panggilan suara saja sebagai default, video bisa dinyalakan di tengah panggilan.
- [ ] Perbaiki parsing frame di `VideoStreaming`: `read()` dianggap selalu penuh, buffer 1 MB dialokasikan per frame, satu thread baru per frame.

## Fase 6 - Jaringan tanpa router / tanpa internet (P1)

Banyak situasi tidak ada router. Dukung:

- [ ] Deteksi dan tampilkan status: terhubung WiFi tanpa internet tetap OK (jangan bergantung pada konektivitas internet).
- [ ] Pakai `ConnectivityManager.bindProcessToNetwork` atau `Network.getSocketFactory()` supaya socket tetap lewat WiFi walau Android memindah ke data seluler karena WiFi "tanpa internet".
- [ ] **Skenario tanpa WiFi: pakai hotspot (P1)**. Satu HP menyalakan hotspot, HP lain bergabung. Tidak butuh kuota data atau internet.
  - [ ] Hotspot buatan user (tethering biasa): IP host biasanya tetap (mis. `192.168.43.1` / `192.168.x.1`), klien dapat IP DHCP. Ambil gateway dari `DhcpInfo` dan sediakan sebagai IP host default.
  - [ ] Hotspot lewat aplikasi (`WifiManager.startLocalOnlyHotspot`, Android 8+): tampilkan SSID/password atau QR untuk bergabung. Perlu izin lokasi / `NEARBY_WIFI_DEVICES`.
  - [ ] Deteksi peran otomatis: perangkat yang hotspot-nya aktif jadi "host", yang lain "klien". Jangan bergantung pada mDNS/broadcast saja karena sebagian hotspot memblokir komunikasi antar klien (client isolation). Klien selalu bisa menghubungi host lewat IP gateway, jadi host menjadi relay daftar peer.
  - [ ] Jika client isolation aktif: semua panggilan lewat host (host meneruskan signaling dan audio), atau beri peringatan di UI.
  - [ ] Tombol "Mulai hotspot" dan "Gabung" di layar awal, dengan petunjuk langkah demi langkah.
- [ ] **WiFi Direct / WiFi Aware** sebagai mode tanpa router (P2).
- [ ] Panduan di aplikasi: cara menghubungkan perangkat ke WiFi yang sama.

## Fase 7 - Keamanan (P1)

- [ ] **Hapus deserialisasi `ObjectInputStream`** di `ProfileServer`. Pakai JSON/protobuf dengan batas ukuran (foto maksimal sekian KB, validasi input).
- [ ] Pairing perangkat (kode PIN atau QR) dan daftar perangkat terpercaya; tolak panggilan dari perangkat tak dikenal (atau minta izin).
- [ ] Enkripsi: TLS dengan sertifikat self-signed + pinning dari pairing untuk signaling, dan SRTP/enkripsi audio-video dengan kunci hasil pairing.
- [ ] Rate-limit permintaan masuk; jangan jalankan socket server tanpa batas koneksi.
- [ ] Notifikasi/indikator jelas saat mikrofon atau kamera sedang aktif.

## Fase 8 - UI/UX (P2)

- [ ] Layar utama: daftar perangkat online (nama, foto, status) dengan tombol Panggil.
- [ ] Layar panggilan: durasi, mute, speaker, video on/off, akhiri.
- [ ] Layar settings: nama, foto, auto-answer, kualitas, mulai saat boot.
- [ ] Pakai ViewModel + state yang bertahan saat rotasi (sekarang `setContentView` dipakai sebagai navigasi).
- [ ] Ganti `FragmentTransaction` statis dan state global statis (`MainActivity.mic`, `mUrlList_as_StringArray`) dengan arsitektur yang jelas.
- [ ] Lokalisasi (Indonesia + Inggris) dan hapus string/log yang tidak pantas (mis. log di `Utilities.convertArrayListToStringArray`).

## Fase 9 - Kualitas dan rilis

- [ ] Unit test: state machine panggilan, parser protokol, jitter buffer.
- [ ] Instrumented test: dua emulator/perangkat saling memanggil di jaringan yang sama.
- [ ] Uji skenario: layar mati/terkunci, app terbunuh, WiFi putus-sambung, panggilan bersamaan, jaringan tanpa internet.
- [ ] Uji latensi (target audio di bawah 200 ms) dan konsumsi baterai saat standby.
- [ ] Logging yang bisa dimatikan (ganti `System.out.println` dan `printStackTrace`), dan crash reporting lokal.
- [ ] Update `README.md` dengan arsitektur, port, protokol, dan cara build.

---

## Urutan pengerjaan yang disarankan (MVP dulu)

1. **Fase 0 + 1** - aplikasi bisa build dan berjalan di Android modern, service menerima panggilan.
2. **Fase 2 + 3** - temukan perangkat, panggil, terima/tolak, tutup.
3. **Fase 4** - panggilan suara yang layak dipakai (inti kebutuhan "telepon tanpa data").
4. **Fase 7** (minimal: hapus `ObjectInputStream` + pairing) - sebelum dipakai di jaringan umum.
5. **Fase 6** - tahan di jaringan tanpa internet.
6. **Fase 5 + 8 + 9** - video, polesan UI, dan pengujian.

## Port dan protokol yang disarankan (target)

| Fungsi | Transport | Catatan |
|---|---|---|
| Discovery | mDNS/NSD (+ UDP multicast fallback) | `_twoway._tcp` |
| Signaling | TCP + TLS | pesan INVITE/ACCEPT/... |
| Audio | UDP (RTP/Opus) | latensi rendah |
| Video | UDP (RTP/H.264) | opsional |
