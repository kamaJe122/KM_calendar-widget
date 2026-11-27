### ⚡ v3.4.0 重大同步架構升級：雙階段秒速雲端同步引擎 ＆ 重複行程刪除徹底修復

- 🚀 **全新雙階段秒速同步引擎（`CalendarSyncEngine`）**
  - **第一階段（0.0 秒啟動極速單向上傳）**：全面改用 Android 12–16 原生加急工作排程（`SYNC_EXTRAS_SCHEDULE_AS_EXPEDITED_JOB` + `SYNC_EXTRAS_UPLOAD = true` + `SYNC_EXTRAS_MANUAL = true`），跳過耗時的全日曆下載掃描，直接將變更／刪除推送至 Google 雲端（網路順暢且未受系統省電封鎖時約 **0.6～1.2 秒**完成上傳）！
  - **解除大量刪除靜默凍結（`SYNC_EXTRAS_OVERRIDE_TOO_MANY_DELETIONS`）**：徹底解決連續刪除多筆測試行程時，Google 系統 SyncAdapter 觸發防誤刪閾值而靜默卡住 `DELETED=1` 佇列（導致 KM 已消失、Google 日曆卻一直還在）的問題。
  - **第二階段（雲端確認後立即喚醒 Google 日曆私有快取）**：每 0.25 秒即時監測 `DIRTY` 佇列歸零，確認上傳成功瞬間自動喚醒 Google 日曆 App 內部 SyncAdapter，加速雙邊畫面一致。
  - **啟動自動清償積壓佇列**：更新開啟 App 時，會自動掃描並強制推送先前因省電模式卡住的待刪除／待修改行程！
- 🛠️ **徹底修復 4 項重複行程／例外事件刪除與編輯底層漏洞**
  - 修正「這個和後續活動」刪除或拆分時，未一併清除未來曾單獨修改過的獨立例外事件（Detached Exceptions）導致殘留的問題。
  - 修正「所有活動」刪除整個重複系列時，一併清理子例外事件列。
  - 修正「只刪除這一次活動」插入 `CONTENT_EXCEPTION_URI` 時的回傳 ID 驗證、`DURATION` 欄位自動修復與 `EXDATE` 保底機制，杜絕假性刪除。
  - 修正單次修改重複行程時長與狀態（`STATUS_CONFIRMED`）以及提醒變更後的即時加急上傳。
- 📡 **雙向即時監聽 ＋ 省電模式攔截偵測與一鍵無限制設定**
  - 主畫面新增 `CalendarContract` 即時 `ContentObserver`（外部日曆異動 **0.14 秒**自動刷新畫面）並縮短返回前景時的 Notion 同步冷卻。
  - 頂部新增即時雲端同步狀態條（顯示實際上傳秒數，如 `✓ Google 雲端已同步完成（0.8 秒）`）；若偵測到 Android **省電模式**正在凍結 Google 背景同步，會主動提醒並提供側邊欄 **「⚡ 雲端同步診斷 · 1 秒極速設定」** 一鍵將「Google 日曆」與「日曆儲存空間」設為電池「無限制」！

[build:15]
