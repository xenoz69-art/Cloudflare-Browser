# CLOUDFLARE BROWSER

## Product Requirements Document (PRD)

**Versi:** 1.0\
**Platform:** Android\
**Minimum OS target:** Android 5.0 (API 21)\
**Teknologi utama:** Java, HTML, CSS, JavaScript, Android System
WebView\
**DNS provider utama:** Cloudflare DNS-over-HTTPS (DoH)\
**Lingkungan pengembangan:** Termux + OpenAI Codex CLI

------------------------------------------------------------------------

# 1. Ringkasan Produk

Cloudflare Browser adalah aplikasi browser Android yang mengutamakan
privasi, keamanan, performa, dan pengalaman penggunaan yang matang.
Aplikasi menggunakan Android System WebView sebagai mesin browser, UI
berbasis HTML/CSS/JavaScript, serta komponen native Android berbasis
Java.

Fitur pembeda utama adalah integrasi Cloudflare DNS-over-HTTPS (DoH)
untuk resolusi hostname pada lalu lintas browser yang benar-benar
melewati jalur proxy/resolver yang dikonfigurasi.

Aplikasi tidak memerlukan root, tidak menggunakan `VPNService`, dan
tidak mengubah DNS sistem Android.

## Tujuan produk

-   Menyediakan browser Android yang ringan dan mudah digunakan.
-   Mengintegrasikan Cloudflare DoH dengan jalur jaringan yang dapat
    diverifikasi.
-   Menyediakan fitur browser esensial seperti tab, bookmark, riwayat,
    dan download.
-   Memiliki UI modern dengan sentuhan estetika anime/Jepang yang subtil
    dan dewasa.
-   Menjaga kompatibilitas sesuai kemampuan Android dan versi WebView
    perangkat.

## Bukan tujuan produk

-   Mengklaim anonimitas penuh atau perlindungan terhadap seluruh bentuk
    pelacakan.
-   Mengubah DNS perangkat atau aplikasi lain.
-   Mengakali batasan WebView atau Android dengan fallback tersembunyi.
-   Melakukan TLS interception atau menonaktifkan validasi sertifikat.

------------------------------------------------------------------------

# 2. Target Pengguna dan Platform

## Target platform

-   Android 5.0/API 21 sebagai target minimum awal.
-   Tidak membutuhkan root.
-   Tidak membutuhkan `VPNService`.
-   Tidak mengubah pengaturan DNS sistem.
-   UI dioptimalkan untuk layar smartphone.

## Teknologi

-   Java untuk komponen native Android.
-   HTML untuk struktur UI.
-   CSS untuk styling dan layout.
-   JavaScript untuk interaksi UI.
-   Android System WebView untuk rendering situs.
-   AndroidX WebKit jika fitur yang diperlukan tersedia.
-   Gradle untuk build APK.

**Catatan kompatibilitas:** Minimum SDK 21 tidak menjamin seluruh fitur
jaringan yang dibutuhkan tersedia pada setiap versi Android System
WebView. Aplikasi wajib memeriksa dukungan aktual saat runtime dan
menjelaskan fitur yang tidak didukung.

------------------------------------------------------------------------

# 3. Prinsip Produk dan Pengalaman Pengguna

-   Mobile-first dan mudah digunakan dengan satu tangan.
-   Navigasi browser harus jelas dan konsisten.
-   UI harus terasa seperti produk browser sungguhan, bukan sekadar
    WebView dengan address bar.
-   Sentuhan anime/Jepang digunakan secara subtil, bukan memenuhi
    seluruh layar.
-   Prioritaskan keterbacaan, aksesibilitas, performa, dan fungsi.
-   Animasi harus ringan dan dapat dikurangi atau dimatikan.
-   Hindari elemen dekoratif yang mengganggu aktivitas browsing.

------------------------------------------------------------------------

# 4. Arahan UI/UX dan Identitas Visual

## 4.1 Arah kreatif

Gunakan konsep **Modern Japanese Digital Aesthetic** dengan inspirasi
yang dapat mencakup:

-   Minimalisme desain digital Jepang.
-   Ilustrasi anime modern yang subtil.
-   Detail visual bertema Jepang, misalnya sakura, langit malam, city
    lights, atau motif geometris Jepang.
-   Nuansa teknologi/cyberpunk Jepang secara terkendali.
-   Tipografi yang bersih dan mudah dibaca.

Hasil akhir harus terlihat premium, orisinal, dan fungsional. Jangan
meniru secara langsung identitas visual produk lain atau menggunakan
aset berhak cipta tanpa izin.

## 4.2 Kebebasan keputusan desain

**Codex bertanggung jawab memilih sendiri color palette, kombinasi
warna, tipografi, ikon, radius, spacing, elevasi, dan detail visual
lainnya.**

Jangan menetapkan palet warna tertentu di awal. Sebelum implementasi UI,
Codex harus:

1.  Meninjau kebutuhan produk dan target pengguna.
2.  Memilih sistem warna yang koheren untuk light mode dan dark mode.
3.  Memastikan kontras teks dan elemen interaktif memadai.
4.  Memastikan warna status DNS, keamanan, error, dan peringatan mudah
    dibedakan.
5.  Mendokumentasikan alasan keputusan desain di `DESIGN_SYSTEM.md`.
6.  Menyimpan token desain sebagai CSS variables agar mudah disesuaikan.

Hindari penggunaan satu warna aksen secara berlebihan, gradient
berlebihan, glassmorphism berlebihan, emoji sebagai pengganti ikon, dan
tampilan generik hasil AI.

## 4.3 Penggunaan ilustrasi

-   Ilustrasi anime/Jepang boleh digunakan pada Home, onboarding, atau
    empty states.
-   Ilustrasi tidak boleh mengganggu address bar, konten situs, atau
    kontrol browser.
-   Hindari karakter anime besar yang mendominasi seluruh UI.
-   Pastikan aset memiliki lisensi yang sesuai atau dibuat khusus untuk
    proyek ini.

------------------------------------------------------------------------

# 5. Struktur dan Navigasi Aplikasi

Halaman/area utama:

1.  Home.
2.  Browser.
3.  Tab Manager.
4.  Bookmarks.
5.  Browsing History.
6.  Downloads.
7.  DNS Settings.
8.  Privacy Settings.
9.  General Settings.
10. About.

Navigasi harus konsisten dan mudah dijangkau pada layar smartphone.
Sediakan state loading, empty, error, dan success untuk setiap fitur
yang relevan.

------------------------------------------------------------------------

# 6. Home Page

Fitur:

-   Search/address field.
-   Pilihan mesin pencari.
-   Shortcut situs favorit.
-   Recently visited websites jika history diaktifkan.
-   Bookmark shortcuts.
-   Indikator status DNS yang akurat.
-   Akses cepat ke tab, bookmark, history, downloads, dan settings.
-   Ilustrasi atau aksen anime/Jepang secara subtil.

Home harus ringan, cepat dibuka, dan tidak memuat konten eksternal yang
tidak diperlukan.

------------------------------------------------------------------------

# 7. Browser Engine dan Navigasi

Gunakan Android System WebView.

Fitur:

-   Address bar dan URL navigation.
-   Pencarian melalui mesin pencari yang dipilih.
-   Back, forward, reload, stop, dan home.
-   Multi-tab.
-   Tab switching dan tab closing.
-   Judul halaman.
-   Favicon jika tersedia.
-   Loading progress.
-   HTTPS/security indicator.
-   Fullscreen untuk situs yang mendukung.
-   Halaman error yang informatif.
-   Penanganan redirect.
-   Penanganan tautan eksternal secara aman.

Jangan menonaktifkan validasi sertifikat TLS. Jangan mengabaikan SSL
errors. Jangan memberi izin JavaScript bridge yang tidak diperlukan.

------------------------------------------------------------------------

# 8. Cloudflare DNS-over-HTTPS

Ini merupakan fitur inti produk.

## 8.1 Konfigurasi resolver

Resolver Cloudflare:

-   IPv4 utama: `1.1.1.1`
-   IPv4 sekunder: `1.0.0.1`
-   DoH endpoint: `https://cloudflare-dns.com/dns-query`

Gunakan implementasi DoH yang mengikuti spesifikasi DNS-over-HTTPS dan
memvalidasi sertifikat TLS. Dokumentasikan format request/response yang
digunakan.

## 8.2 Arsitektur jaringan yang diharapkan

Arsitektur kandidat:

`WebView → WebView proxy integration → Local HTTP proxy → DNS resolver via Cloudflare DoH → tujuan jaringan`

Komponen dapat disesuaikan berdasarkan hasil verifikasi teknis. Jangan
menganggap DoH lookup yang berhasil secara terpisah berarti seluruh
request WebView telah menggunakan resolver tersebut.

## 8.3 Local proxy

Jika pendekatan proxy digunakan, proxy harus:

-   Bind hanya ke loopback/localhost.
-   Tidak menerima koneksi dari LAN atau internet.
-   Mendukung HTTP CONNECT untuk HTTPS tunneling.
-   Menangani koneksi bersamaan dengan aman.
-   Memiliki timeout dan penanganan kegagalan.
-   Melakukan resolusi hostname melalui Cloudflare DoH.
-   Memiliki cache DNS dengan TTL yang benar.
-   Tidak melakukan TLS interception.
-   Tidak mengubah atau memalsukan sertifikat situs.

## 8.4 Integrasi WebView

Evaluasi AndroidX WebKit `ProxyController` dan dukungan proxy override
pada WebView perangkat.

-   Periksa ketersediaan API sebelum mengaktifkan fitur.
-   Tunggu callback penerapan konfigurasi proxy sebelum menganggapnya
    aktif.
-   Verifikasi bahwa request WebView benar-benar menggunakan proxy.
-   Periksa perilaku DNS untuk navigasi, redirect, subresource,
    WebSocket, dan koneksi lain yang relevan.
-   Jika fitur tidak didukung, tampilkan status unsupported.
-   Jangan diam-diam mengalihkan trafik ke resolver sistem sambil tetap
    menampilkan status DNS aktif.

Jika arsitektur proxy tidak dapat menjamin semua kriteria, Codex harus
mengidentifikasi batasannya dan mengusulkan alternatif yang jujur secara
teknis sebelum mengklaim fitur selesai.

## 8.5 DNS Settings

Sediakan:

-   Enable/disable Cloudflare DNS.
-   Status resolver.
-   Status proxy/WebView integration.
-   DNS latency/diagnostic test.
-   Test koneksi DoH.
-   Clear DNS cache.
-   Pilihan primary/secondary resolver.
-   Pesan error yang dapat dipahami pengguna.
-   Waktu koneksi terakhir yang berhasil.

Pisahkan status berikut agar tidak menyesatkan:

1.  DoH endpoint reachable.
2.  DNS query berhasil.
3.  Proxy berjalan.
4.  WebView proxy override diterapkan.
5.  Trafik WebView telah diverifikasi melewati jalur yang dimaksud.

## 8.6 Fallback dan kegagalan

-   Resolver sekunder hanya digunakan sesuai kebijakan fallback yang
    terdokumentasi.
-   Jangan fallback diam-diam ke DNS sistem.
-   Jika jalur DNS yang diwajibkan gagal, tampilkan kegagalan secara
    jelas.
-   Jangan menyatakan DNS aktif apabila integrasi WebView/proxy tidak
    aktif atau belum terverifikasi.

------------------------------------------------------------------------

# 9. Tab Management

Fitur:

-   Membuka tab baru.
-   Menutup tab.
-   Berpindah tab.
-   Duplikasi tab.
-   Tab counter.
-   Preview tab.
-   Memulihkan sesi terakhir jika diaktifkan.
-   Menangani lifecycle WebView untuk menghindari kebocoran memori.

Setiap tab harus mempertahankan state navigasi yang sesuai. Penggunaan
memori harus diperhatikan pada perangkat dengan RAM terbatas.

------------------------------------------------------------------------

# 10. Bookmark

Fitur:

-   Tambah bookmark.
-   Hapus bookmark.
-   Edit nama dan URL.
-   Folder/kategori bookmark.
-   Pencarian bookmark.
-   Pengurutan bookmark.
-   Import/export bookmark jika memungkinkan.

Data disimpan secara lokal dan ditangani dengan aman.

------------------------------------------------------------------------

# 11. Browsing History

Fitur:

-   Menyimpan URL, judul, serta waktu kunjungan sesuai pengaturan.
-   Pencarian history.
-   Hapus satu item.
-   Hapus berdasarkan rentang waktu.
-   Hapus seluruh history.
-   Pengaturan untuk menonaktifkan pencatatan.

Private browsing tidak boleh menyimpan history permanen.

------------------------------------------------------------------------

# 12. Download Manager

Fitur:

-   Download file dari situs.
-   Menampilkan progres.
-   Cancel download.
-   Retry untuk download gagal.
-   Membuka file hasil download.
-   Membagikan file.
-   Riwayat download.

Pause/resume hanya disediakan jika mekanisme download benar-benar
mendukungnya. Gunakan mekanisme penyimpanan Android yang sesuai dengan
versi OS dan izin yang tersedia.

------------------------------------------------------------------------

# 13. Private Browsing

Fitur:

-   Sesi private terpisah.
-   Tidak menyimpan history permanen.
-   Tidak menyimpan form data secara permanen.
-   Penanganan cookie dan cache sesuai kemampuan WebView.
-   Indikator mode private.
-   Menghapus data sesi ketika seluruh private tabs ditutup.

Jangan mengklaim private browsing membuat pengguna anonim atau mencegah
seluruh pelacakan di internet. Dokumentasikan batasannya.

------------------------------------------------------------------------

# 14. Settings

## General

-   Homepage.
-   Search engine.
-   Startup behavior.
-   Language.

## Appearance

-   Light mode.
-   Dark mode.
-   Follow system.
-   Ukuran teks.
-   Pengaturan animasi.

## Privacy

-   Clear browsing data.
-   Cookie controls sesuai dukungan WebView.
-   JavaScript controls.
-   Pop-up controls.
-   Pengaturan history.

## DNS

-   Cloudflare DoH settings.
-   DNS diagnostics.
-   Proxy status.
-   Resolver status.
-   DNS cache.
-   Error details.

------------------------------------------------------------------------

# 15. Performance Requirements

-   Startup cepat.
-   Penggunaan memori efisien.
-   Scroll dan interaksi UI lancar.
-   Animasi ringan.
-   Pengelolaan tab yang efisien.
-   Lifecycle WebView yang benar.
-   Tidak ada memory leak.
-   Tidak membuat koneksi atau thread yang tidak diperlukan.
-   Tetap usable pada perangkat dengan sumber daya terbatas.

------------------------------------------------------------------------

# 16. Security Requirements

-   Validasi sertifikat HTTPS tetap aktif.
-   Tidak ada SSL bypass.
-   Tidak ada credential rahasia yang di-hardcode.
-   Local proxy hanya bind ke localhost.
-   Validasi URL dan input pengguna.
-   Batasi JavaScript bridge.
-   Amankan proses download dan akses file.
-   Lindungi data private browsing.
-   Cegah path traversal.
-   Jangan mengekspos local proxy ke jaringan luar.
-   Jangan mencatat password, token, atau konten sensitif.
-   Dokumentasikan threat model dan batasan keamanan.

------------------------------------------------------------------------

# 17. Struktur Proyek

Gunakan struktur modular yang dapat disesuaikan:

``` text
cloudflare-browser/
├── app/
│   └── src/main/
│       ├── java/com/cloudflare/browser/
│       │   ├── MainActivity.java
│       │   ├── browser/
│       │   ├── dns/
│       │   ├── proxy/
│       │   ├── tabs/
│       │   ├── bookmarks/
│       │   ├── history/
│       │   ├── downloads/
│       │   ├── privacy/
│       │   └── settings/
│       ├── assets/
│       │   ├── index.html
│       │   ├── css/
│       │   ├── js/
│       │   ├── pages/
│       │   └── fonts/
│       ├── res/
│       └── AndroidManifest.xml
├── gradle/
├── build.gradle
├── settings.gradle
├── gradle.properties
├── PRD.md
├── SKILLS_AUDIT.md
├── DESIGN_SYSTEM.md
├── ARCHITECTURE.md
├── IMPLEMENTATION_PLAN.md
├── TESTING_REPORT.md
├── SECURITY_REPORT.md
└── README.md
```

Struktur boleh berkembang jika diperlukan. Hindari memaksakan semua
logika ke satu file besar.

------------------------------------------------------------------------

# 18. Testing Requirements

## Browser

-   HTTP navigation.
-   HTTPS navigation.
-   Redirect.
-   Multiple tabs.
-   JavaScript execution.
-   Website dengan banyak subresources.
-   Downloads.
-   Bookmarks.
-   History.
-   Private browsing.

## DNS dan proxy

-   Cloudflare DoH connectivity.
-   Query DNS primary.
-   Query DNS secondary.
-   DNS timeout.
-   DNS cache dan TTL.
-   Invalid hostname.
-   Resolver fallback.
-   Proxy startup/shutdown.
-   HTTP CONNECT.
-   TLS passthrough.
-   Proxy failure.
-   Verifikasi jalur DNS dan potensi DNS leak.

## Compatibility

Uji atau dokumentasikan hasil untuk:

-   Android 5.0.
-   Android 7.
-   Android 10.
-   Android 12.
-   Android 14.
-   Android 15.

Jangan mengklaim hasil pengujian pada perangkat yang tidak benar-benar
tersedia. Bedakan hasil pengujian aktual, automated test, dan analisis
kompatibilitas.

------------------------------------------------------------------------

# 19. Acceptance Criteria

Proyek dapat dinyatakan siap untuk rilis awal apabila:

1.  APK berhasil dibangun.
2.  Aplikasi dapat dibuka pada perangkat target yang diuji.
3.  Browser dapat membuka situs HTTPS dengan validasi TLS normal.
4.  Address bar dan navigasi berfungsi.
5.  Multi-tab berfungsi.
6.  Bookmark dan history berfungsi.
7.  Download manager berfungsi sesuai spesifikasi.
8.  DoH query ke Cloudflare berhasil.
9.  Proxy berjalan hanya pada localhost jika digunakan.
10. Integrasi proxy WebView berhasil diterapkan pada perangkat yang
    mendukung.
11. Trafik WebView yang diklaim terlindungi telah diverifikasi
    menggunakan metode pengujian yang terdokumentasi.
12. DNS failure ditangani secara eksplisit.
13. Tidak ada silent fallback yang menampilkan status DNS aktif secara
    keliru.
14. Tidak ada SSL bypass.
15. UI responsif pada layar smartphone.
16. Keterbatasan kompatibilitas dan keamanan didokumentasikan.

------------------------------------------------------------------------

# 20. Development Milestones

## Milestone 1 --- Foundation

-   Audit lingkungan build.
-   Inisialisasi proyek Android.
-   Konfigurasi Gradle.
-   Native Activity.
-   WebView.
-   UI shell.
-   Design system.
-   Home dan navigasi dasar.

## Milestone 2 --- Browser Core

-   Address bar.
-   Navigasi.
-   Loading state.
-   Error state.
-   Multi-tab.
-   Lifecycle dan memory handling.

## Milestone 3 --- DNS Core

-   DoH resolver.
-   Local proxy jika pendekatan ini layak.
-   HTTP CONNECT.
-   WebView proxy integration.
-   DNS diagnostics.
-   Failure handling.
-   Verifikasi jalur trafik.

## Milestone 4 --- Browser Features

-   Bookmark.
-   History.
-   Downloads.
-   Private browsing.
-   Settings.

## Milestone 5 --- Quality and Release

-   UI refinement.
-   Accessibility.
-   Performance review.
-   Security audit.
-   Compatibility testing.
-   Build APK.
-   README dan laporan akhir.

------------------------------------------------------------------------

# 21. Definition of Done

Source code yang selesai dibuat tidak otomatis berarti produk selesai.

Dilarang:

-   Menggunakan pseudocode sebagai implementasi final.
-   Membuat tombol tanpa fungsi.
-   Menampilkan indikator DNS palsu.
-   Menggunakan dummy functionality tanpa label.
-   Mengabaikan compile errors.
-   Mengabaikan security warnings yang relevan.
-   Mengklaim kompatibilitas tanpa bukti.
-   Mengklaim seluruh trafik menggunakan DoH hanya karena satu DNS test
    berhasil.

Setiap fitur yang belum dapat diselesaikan harus diberi status, alasan,
dampak, dan langkah lanjutan.

**Prioritas akhir:** 1. Functional correctness. 2. DNS security dan
verifiability. 3. Android compatibility. 4. UI/UX quality. 5.
Performance. 6. Maintainability.
