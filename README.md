# KM 行事曆 ＆ 桌面小工具 (KM Calendar & Widget)

極致輕量（純原生 Java、零肥大依賴）的 **Google 行事曆風格完整行事曆 App ＋ 5×2 桌面月曆小工具**，同時整合：
- 🗓️ **5 種完整行事曆檢視模式**：**月（`月`）、週（`週`）、3 天（`3 天`）、單日（`天`）、時間表（`時間表`）**，內建台灣繁體農曆干支與初一～三十顯示、左右滑動翻頁、雙指縮放時間軸高度、即時搜尋與快速新增行程
- 🎨 **外觀與習慣自由切換**：支援 **「淺色（Google 行事曆清爽風）／深色／跟隨系統」** 與 **「週日起始／週一起始」**
- 📅 **手機系統行事曆資料庫**（Google 日曆等帳號同步、原生新增與刪除行程）
- 📓 **Notion 官方 API（`2026-03-11` Data Sources & Views）日曆串接**（自動抓出工作區所有資料庫與 Calendar / Timeline 多檢視表、套用檢視篩選、自訂代表色、點擊詳情一鍵開啟 Notion 頁面）
- 🧩 **5×2 桌面小工具（支援多個小工具各自獨立設定）**：不同桌面頁或 LockDock 上的小工具可分別設定「是否顯示全天行程」、「顯示天數」與「勾選哪些 Google／Notion 日曆」，點擊小工具直接連動開啟本 App 對應日期與行程詳情卡
- 🚀 **App 內建 GitHub Releases OTA 更新安裝**（頂部右上角設定按鈕或側邊欄進入「小工具 · Notion · 更新設定」即可一鍵檢查下載覆蓋更新）

---

## 安裝與 App 內更新說明

1. 前往 [**Releases**](https://github.com/kamaJe122/KM_calendar-widget/releases) 下載最新的 `KM_calendar-widget.apk`。
2. **v2.0.0 之後的所有版本**：直接打開 App → 點右上角調校圖示（或左側選單 **「⚙️ 桌面小工具設定 · Notion 串接 · 更新」**）→ 在最上方點 **「檢查更新」→「下載並安裝新版」** 即可直接覆蓋升級，所有設定與 Notion Token 完整保留！

---

## Notion 日曆串接步驟

1. 進入 App 右上角設定頁，在「**Notion 日曆串接**」區塊點擊 **「取得 Notion Token ／ 設定 Access 權限」**（`https://www.notion.so/profile/integrations`），建立 **Internal Integration** 並複製 Secret Token（`ntn_...`）。
2. 在 Notion Integration 設定頁切到 **「Access（存取權限）」** 分頁，勾選你要顯示的原始日曆資料庫並儲存（或在 Notion 進入該資料庫右上角 `•••` → `Connections` 加入此 Integration）。
3. 回到設定頁貼上 Token 並點 **「連接並同步 Notion」**，即可同時在 **KM 行事曆主畫面** 與 **桌面小工具** 中顯示並自由切換顏色！

---

## 建置與發佈新版本

```bash
./gradlew assembleRelease
```

推送 `v<versionName>-b<versionCode>` 格式的 Git Tag（例如 `v3.0.0-b9`），GitHub Actions（`.github/workflows/release.yml`）即會自動編譯簽章並發佈新版 Release。
