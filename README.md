# KM 桌面行事曆小工具 (KM Calendar Widget)

極致輕量（純原生 Java、零肥大依賴）的 Android **5×2 桌面月曆＋多日行程小工具**，同時整合：
- 📅 **手機系統行事曆資料庫**（Google 日曆等帳號同步）
- 📓 **Notion 官方 API（`2026-03-11` Data Sources & Views）日曆串接**（支援自動抓出工作區所有資料庫與 Calendar / Timeline 多檢視表、套用檢視篩選條件、自訂每個日曆檢視的代表色、點擊小工具行程直接開啟 Notion 對應頁面）
- 🧩 **多個小工具各自獨立設定**（不同桌面頁或 LockDock 上的小工具可分別設定「是否顯示全天行程」、「顯示天數」與「要勾選哪些 Google／Notion 日曆」，並可自訂小工具別名）
- 🚀 **App 內建 GitHub Releases OTA 更新安裝**（一鍵檢查新版、顯示下載進度並直接呼叫系統安裝器覆蓋更新）

---

## 安裝與 App 內更新說明

1. 前往 [**Releases**](https://github.com/kamaJe122/KM_calendar-widget/releases) 下載最新的 `KM_calendar-widget.apk`。
2. **若先前安裝過舊版（v1.5 單機版）**：因 v2.0.0 起改用專案專屬正式簽章（安全儲存於 GitHub Actions Secrets），**第一次**升級請先移除手機上的舊版 v1.5 再安裝新版。
3. **v2.0.0 之後的所有更新**：直接打開「KM 桌面行事曆小工具」App → 在最上方「**App 內版本更新（GitHub）**」卡片點 **「檢查更新」→「下載並安裝新版」** 即可直接覆蓋更新，所有設定與 Notion Token 都會完整保留！

---

## Notion 日曆串接步驟（3 分鐘完成）

> 由於 Notion Calendar App 並不會把 Notion 資料庫寫入 Android 系統行事曆資料庫，本小工具透過 Notion 官方 API 直接讀取並快取在手機本機（Token 只儲存在你的手機內，絕不會上傳）。

1. 打開 App，在「**Notion 日曆串接**」區塊點擊 **「取得 Notion Token」**（或前往 `https://www.notion.so/profile/integrations`），建立一個 **Internal Integration** 並複製 Secret Token（`ntn_...` 或 `secret_...`）。
2. **授權資料庫存取權限（二選一）**：
   - **方法 A（推薦）**：在 Notion Integration 設定頁切到 **「Access（存取權限）」** 分頁，直接勾選你要顯示的日曆資料庫（注意：若頁面只是插入 Linked View，請勾選**原始資料庫本體**）並儲存。
   - **方法 B**：打開 Notion 進入該**原始日曆資料庫頁面**，點右上角 **`•••` → `Connections（連結）`**，把剛建立的 Integration 加進去。
3. 回到本 App 貼上 Token 並點 **「連接並同步 Notion」**：
   - App 會自動抓出該工作區（例如 `李哲逸 Kama`）底下所有含日期欄位的資料庫，以及所有的 **Calendar / Timeline 檢視表**（例如同一個 `🎬 MakeVDO行事曆` 資料庫若有多個不同篩選條件的日曆檢視，會自動拆成多個獨立項目供勾選，並在右側顯示抓到的近期行程筆數 `（N 筆）`）。
   - 若某個資料庫沒被搜尋自動列出，可點 **「🔗 貼上資料庫連結／ID 加入」** 直接貼上 Notion 資料庫網址匯入並檢測權限。
   - 點擊每個 Notion 項目左側的 **彩色方塊** 即可自由調整小工具上的行程色條顏色；若想把某個 Table / Board 檢視或第二個日期欄位也單獨加成一列日曆，可點 **「＋ 新增資料庫其他檢視／日期欄位」**。

---

## 建置與發佈新版本

```bash
./gradlew assembleRelease
```

發佈 GitHub Release 時，Tag 請採用 `v<versionName>-b<versionCode>` 格式（例如 `v2.1.0-b8`），App 內建更新器會自動解析 `-b<versionCode>` 判斷是否有新版本可供更新。儲存庫亦已內建 GitHub Actions（`.github/workflows/release.yml`），推送 `v*` Tag 或手動觸發 Workflow 即可自動編譯簽章並發佈 Release。
