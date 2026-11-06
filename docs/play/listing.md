# Google Play listing

Copy into Play Console → Grow → Store presence → Main store listing. Default language: Ukrainian (uk-UA), translation: English (en-US).

Graphics in this folder: `icon-512.png`, `feature-graphic-1024x500.png`. Phone screenshots: take them on a device (min 2, 9:16), or use `docs/screenshots/`.

## uk-UA

**Назва (≤30):** Світлограф: графік відключень

**Короткий опис (≤80):** Віджет із графіком відключень світла для вашої черги: відлік, сповіщення, кілька адрес

**Повний опис (≤4000):**

Світлограф показує на робочому столі, чи є зараз світло за вашою адресою і коли його вимкнуть або увімкнуть.

• Віджет із живим відліком до наступного вимкнення чи увімкнення
• Графік на сьогодні й завтра з позначкою «зараз»
• Кілька адрес: дім, робота, батьки — для кожної свій віджет
• Сповіщення, коли вийшов графік на завтра або змінився сьогоднішній
• Нагадування за 5–60 хвилин до вимкнення
• Попередження про екстрені відключення
• Усі регіони України, де публікують графіки черг
• Світла й темна тема, кольори з ваших шпалер
• Українська та англійська мови

Без реклами, акаунтів і збору даних.

Дані беруться з відкритого проєкту UA-power-outages-monitor, який збирає графіки ДТЕК, YASNO та обленерго. Світлограф не пов'язаний із ДТЕК, YASNO, обленерго чи Укренерго. Під час екстрених відключень графіки не діють — у разі сумнівів звіряйтеся з офіційним джерелом.

## en-US

**Title (≤30):** Svitlograf: outage schedule

**Short description (≤80):** Power outage schedule widget for your group: countdown, alerts, several addresses

**Full description (≤4000):**

Svitlograf shows on your home screen whether the power is on at your address right now, and when it goes off or comes back.

• Widget with a live countdown to the next switch
• Today's and tomorrow's schedule with a "now" marker
• Several addresses: home, work, parents — a widget for each
• Notifications when tomorrow's schedule is published or today's changes
• Reminders 5–60 minutes before an outage
• Emergency outage warnings
• Every Ukrainian region that publishes group schedules
• Light and dark themes, colours from your wallpaper
• Ukrainian and English

No ads, no accounts, no data collection.

Data comes from the open UA-power-outages-monitor project, which collects DTEK, YASNO and oblenergo schedules. Svitlograf is not affiliated with DTEK, YASNO, the oblenergos or Ukrenergo. Schedules don't apply during emergency outages — check the official source when in doubt.

## Play Console forms

- **Privacy policy URL:** https://beta.decadance.me/svitlograf/privacy.html (published by `scripts/publish-update.sh`)
- **App access:** all functionality available without special access
- **Ads:** no
- **Content rating:** questionnaire → category "Utility", no objectionable content
- **Target audience:** 18+ (avoids the Families policy; nothing in the app is for children)
- **Data safety:** no data collected, no data shared; data is encrypted in transit (HTTPS)
- **Government apps:** not a government app; the disclaimer above covers the source
- **Category:** Tools (or Weather)
- **Permissions:** `SCHEDULE_EXACT_ALARM` is user-granted, no declaration form needed. The Play build has no `USE_EXACT_ALARM`, `REQUEST_INSTALL_PACKAGES` or `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
