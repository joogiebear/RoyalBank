## 2026.36.0 — 2026-09-06

### ✨ Features
- optional playtime floor on bank transfers (`0674e91`)

### 🐛 Fixes
- announce interest auto-claimed at join (`ab7dbbb`)

### ♻️ Refactors
- custom amounts on a sign instead of public chat (`96c89e2`)

### 📝 Documentation
- state the Paper 26.2-or-newer requirement (`eced269`)

## 2026.32.0 — 2026-08-07

### ✨ Features
- report anonymous usage stats via bStats (`9f1cb43`)

### 🐛 Fixes
- stop menus rendering in italic (`d77fdb7`)

### 📝 Documentation
- document /bank transfer (`4601997`)

## 2026.29.1 — 2026-07-17

### 🐛 Fixes
- pass the Modrinth payload as a file, not inline (`d7a5db4`)

## 2026.29.0 — 2026-07-17

### ✨ Features
- id-keyed full accounts + personal-bank snapshot/restore (`7169127`)
- shared-account API for group/coop balances (`e7972c6`)
- resolve eco item ids in bank icons + auto-generated upgrade-cost icons (`79b954c`)
- player-head icons + eco-style direct row/column (`c60b754`)
- port bank menus to the shared EcoMenus engine (`f9a091b`)

### 🐛 Fixes
- use non-reserved alias 'rn' in prune query for MySQL compatibility (`f6a8f1a`)
- integrate EconGuard by reflection, drop stale dep, remove Gradle (`1bb71b8`)

### ♻️ Refactors
- unify storage on HikariCP with SQLite default + MySQL option (`9dcb207`)
- split bank package into command/ and service/ (`e4809a3`)

### 📝 Documentation
- add full admin and developer README (`5a78d98`)

