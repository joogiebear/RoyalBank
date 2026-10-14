## 2026.42.0 - 2026-10-13

### Features
- refresh cached bank balances changed by another server (`9c15582`)

### Fixes
- update royal-common so a failed balance refresh is retried (`2e38cee`)
- run bank database work off the main thread (`94fe70a`)

## 2026.41.4 - 2026-10-11

### Fixes
- make every bank change one locked transaction (`9ba8375`)

### Documentation
- say each server needs its own database for now (`af0e633`)

## 2026.41.3 - 2026-10-11

### Fixes
- cancel a queued dialog answer when the prompt is dismissed (`439082c`)

## 2026.41.2 - 2026-10-11

### Fixes
- keep coop depositors across restarts (`8ef735a`)
- pay interest on the lowest balance held since the last claim (`5a69ec3`)

## 2026.41.1 - 2026-10-11

### Fixes
- say shorthand amounts work in the custom amount error (`d59c434`)
- report bank movements to EconGuard with the right direction and counterparty (`cd4f818`)

## 2026.41.0 - 2026-10-10

### Refactors
- read all player-facing text from config (`8cbe675`)
- replace the sign input with a native text dialog (`ec2db58`)

### Documentation
- add AGENTS.md with the suite's development standards (`95a09d6`)

## 2026.40.1 — 2026-10-03

### 🐛 Fixes
- keep synchronous=FULL for the money-handling store (`a57a360`)

## 2026.40.0 — 2026-10-03

### 🐛 Fixes
- apply every SQLite pragma, not just the first (`879674e`)
- use the ISO week-year for release versions (`290a631`)

## 2026.39.1 — 2026-09-24

### 🐛 Fixes
- end prompts whose answer can never arrive (`7304516`)
- keep the sign prompt off block entities and blocks already in use (`b441321`)

### 📝 Documentation
- add MIT license (`286c108`)

## 2026.39.0 — 2026-09-23

### 🔧 Other
- paper-api 26.2.build.121-stable -> 26.2.build.123-stable (`0194af1`)

## 2026.37.0 — 2026-09-13

### 🐛 Fixes
- release at 10:00 Central or later, not exactly 10:00 (`fae0a48`)

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

