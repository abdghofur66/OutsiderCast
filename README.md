# OutsiderCast

Share screen **lokal** (satu Wi-Fi/LAN, tanpa internet) untuk presentasi dan mengajar.
Pemateri menjalankan aplikasi di Windows/Mac, lalu penonton cukup scan QR atau buka alamatnya di browser. Penonton tidak perlu install apa pun.

## Fitur
- Bagikan seluruh layar, satu monitor, atau satu jendela (bisa ganti saat berjalan)
- Suara sistem ikut terkirim (khusus Windows)
- Banyak penonton sekaligus, dengan kualitas otomatis menyesuaikan jumlah penonton
- Jeda layar, PIN ruang opsional, daftar penonton, dan tombol keluarkan penonton
- Penonton bisa layar penuh, zoom (tombol/roda mouse/cubit), dan geser
- WebRTC langsung di jaringan lokal, tanpa server luar

## Android (berbagi layar HP)
Instal `OutsiderCast-Android-*.apk` dari Release, ketuk **Mulai berbagi**, lalu izinkan rekam layar. Penonton scan QR dari browser, cukup satu Wi-Fi atau tersambung ke hotspot HP itu. Video dikirim lewat WebRTC dengan encoder hardware (sampai 1080p/30 fps), dan bisa ditonton di browser apa pun termasuk iPhone. Suara belum ikut terkirim. Kode ada di folder `android/`.

## Menjalankan dari kode
```bash
npm install
npm start
```

## Build
```bash
npm run dist:win   # .exe installer + portable
npm run dist:mac   # .dmg (harus di Mac)
```
Atau push tag `v1.0.0` ke GitHub, maka GitHub Actions akan membuild .exe dan .dmg lalu melampirkannya ke Release.
Taruh ikon 512×512 di `build/icon.png`. Kalau tidak ada, workflow otomatis mengambil logo OutsiderDev.

## Catatan
- **Mac:** saat pertama kali dipakai, izinkan *Screen Recording* di System Settings → Privacy & Security, lalu buka ulang aplikasi.
- **Windows:** kalau firewall bertanya, izinkan akses di *Private network* agar penonton bisa terhubung.
- Port bawaannya 8080. Kalau sudah dipakai, aplikasi otomatis mencoba 8081, lalu 8090.
- Untuk kelas besar (30+ penonton), pilih kualitas *Hemat* atau *Otomatis*.

## Lisensi
MIT © Abdul Ghofur Iswahyudi — [OutsiderDev](https://outsiderdev.com)
