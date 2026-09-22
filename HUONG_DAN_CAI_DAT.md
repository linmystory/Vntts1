# Hướng dẫn build APK có tính năng Lưu âm thanh (WAV thật)

Ứng dụng này gồm 2 phần:
1. **Giao diện web** (`www/index.html`) — y hệt bản trước, cộng thêm nút "💾 Lưu âm thanh".
2. **Plugin native Android** (`native-plugin/TtsFileSaverPlugin.kt`) — đoạn code Kotlin thật sự tạo ra file `.wav` bằng bộ máy Text-to-Speech của hệ điều hành.

Vì có code native, bạn **không thể** dùng webintoapp.com nữa. Thay vào đó dùng **Capacitor** + **Android Studio**, đều miễn phí.

---

## Bước 0 — Cài công cụ (chỉ làm 1 lần)

1. Cài **Node.js** (bản LTS): https://nodejs.org
2. Cài **Android Studio**: https://developer.android.com/studio
   - Mở Android Studio ít nhất 1 lần để nó tự tải Android SDK.

---

## Bước 1 — Khởi tạo dự án Capacitor

Mở terminal (Command Prompt / PowerShell / Terminal) tại thư mục chứa toàn bộ các file bạn vừa tải về (`package.json`, `www/`, `native-plugin/`, `capacitor.config.json`), rồi chạy:

```bash
npm install
npx cap add android
```

Lệnh `npx cap add android` sẽ tự sinh ra thư mục `android/` — đây là project Android Studio đầy đủ (Gradle, Manifest, v.v.), bạn không cần tự viết tay.

---

## Bước 2 — Copy plugin native vào đúng vị trí

1. Tạo đường dẫn thư mục sau (nếu chưa có):
   ```
   android/app/src/main/java/com/docdoc/app/
   ```
2. Copy 2 file sau vào đúng thư mục đó:
   - `native-plugin/TtsFileSaverPlugin.kt`
   - `native-plugin/MainActivity.java` (**ghi đè** lên file `MainActivity.java` mặc định đã có sẵn ở đó)

   > Lưu ý: package name trong 2 file này là `com.docdoc.app`, khớp với `appId` trong `capacitor.config.json`. Nếu bạn đổi `appId`, phải đổi cả dòng `package com.docdoc.app` trong file `.kt` và `.java` cho khớp, cũng như đường dẫn thư mục ở trên.

3. **(Tuỳ chọn — chỉ cần nếu muốn hỗ trợ Android 9 trở xuống)**
   - Copy `native-plugin/file_paths.xml` vào `android/app/src/main/res/xml/file_paths.xml` (tạo thư mục `xml` nếu chưa có).
   - Mở `android/app/src/main/AndroidManifest.xml`, thêm 2 đoạn trong file `native-plugin/AndroidManifest-them-vao.xml` vào đúng vị trí được ghi chú trong đó.
   - Nếu bỏ qua bước này, app vẫn hoạt động bình thường trên **Android 10 trở lên** (chiếm đa số thiết bị hiện nay).

---

## Bước 3 — Đồng bộ và mở project trong Android Studio

```bash
npx cap sync android
npx cap open android
```

Lệnh thứ 2 sẽ tự mở Android Studio với project đã sẵn sàng.

---

## Bước 4 — Chạy thử

Trong Android Studio:
- Cắm điện thoại Android qua cáp USB (bật **Tùy chọn nhà phát triển → Gỡ lỗi USB**), hoặc dùng máy ảo (Emulator).
- Bấm nút **Run ▶** (màu xanh lá) trên thanh công cụ.
- App sẽ cài và mở trên máy/máy ảo. Thử nhập văn bản, bấm **"💾 Lưu âm thanh"** — nếu thành công sẽ có thông báo và hộp thoại chia sẻ/lưu file hiện ra. File WAV được lưu tại `Music/DocDocTTS/` trên thiết bị.

---

## Bước 5 — Xuất file APK để cài đặt / chia sẻ

Trong Android Studio, vào menu:

```
Build → Build Bundle(s) / APK(s) → Build APK(s)
```

Sau khi build xong, Android Studio hiện thông báo ở góc dưới màn hình — bấm **"locate"** để mở thư mục chứa file `app-debug.apk`. Copy file này sang điện thoại và cài đặt như bình thường (nhớ bật "Cài từ nguồn không xác định" nếu máy yêu cầu).

> File `app-debug.apk` dùng để **tự cài và test**. Nếu muốn phát hành công khai (Google Play hoặc chia sẻ rộng rãi), cần tạo **bản ký (signed APK / AAB)** qua `Build → Generate Signed Bundle / APK`, kèm theo tạo keystore riêng — phần này Android Studio có hướng dẫn từng bước ngay trong giao diện.

---

## Cách hoạt động của tính năng Lưu âm thanh (tóm tắt)

1. JS trong `www/index.html` gọi `window.Capacitor.Plugins.TtsFileSaver.synthesizeToFile(...)`.
2. Plugin Kotlin nhận văn bản, **tự động chia thành nhiều câu ngắn** (do TTS Android giới hạn độ dài mỗi lần tổng hợp).
3. Mỗi câu được tổng hợp bằng `TextToSpeech.synthesizeToFile()` ra một file WAV nhỏ tạm thời — **quá trình này chạy âm thầm, không phát ra loa**.
4. Plugin **ghép các file WAV nhỏ lại thành 1 file WAV hoàn chỉnh** (copy đúng phần dữ liệu âm thanh, viết lại header WAV cho khớp).
5. File hoàn chỉnh được lưu vào thư mục `Music/DocDocTTS/` trên thiết bị qua `MediaStore` (Android 10+, không cần xin quyền).
6. Plugin trả `Uri` của file về JS → JS gọi tiếp `shareFile()` để mở hộp thoại Chia sẻ/Lưu của Android (Google Drive, Zalo, Gửi qua email, v.v.).

## Những điều cần lưu ý

- **Giọng đọc trong file WAV phụ thuộc vào máy đang build/chạy** — vì dùng chính TTS hệ thống của thiết bị đó, giống hệt như khi phát trực tiếp trong app.
- Văn bản càng dài, quá trình tạo file càng lâu (vì phải tổng hợp tuần tự từng câu). Với 25.000 ký tự có thể mất khoảng 1–3 phút tùy thiết bị — nút "Lưu âm thanh" sẽ hiện "⏳ Đang tạo file âm thanh..." trong lúc chờ.
- File xuất ra là **WAV** (không nén) vì đây là định dạng gốc mà `TextToSpeech.synthesizeToFile()` hỗ trợ trực tiếp, không cần thư viện mã hoá MP3 bên thứ ba.
