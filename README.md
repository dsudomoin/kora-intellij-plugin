# Kora IntelliJ Plugin

[![JetBrains Plugin](https://img.shields.io/jetbrains/plugin/v/30225-kora.svg)](https://plugins.jetbrains.com/plugin/30225-kora)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/30225-kora.svg)](https://plugins.jetbrains.com/plugin/30225-kora)

[Русский](#русский) | [English](#english)

---

## English

IntelliJ IDEA plugin for the [Kora Framework](https://kora-projects.github.io/kora-docs/) — Spring-like navigation, inspections, and code completion for Kora DI.

### Features

**DI Navigation**
- Navigate from injection parameters to their providers and back
- `@Component`, `@Repository`, factory methods in `@KoraApp` / `@Module` / `@KoraSubmodule` / `@Generated`
- Tag resolution: `@Tag`, meta-annotations, `@Tag.Any`
- Wrapper types: `All<T>`, `ValueOf<T>`, generic type matching
- Java and Kotlin (K1/K2)

**Gutter Icons**
- Providers (`@Component`, `@Repository`, factory methods) — navigate to usages
- Injection parameters — navigate to providers
- `@ConfigSource` — navigate to config files

**Config Navigation**
- `@ConfigSource("path")` ↔ keys in `application.yml` / `application.conf` (HOCON)
- Annotation values → config keys:

| Annotation | Config path |
|---|---|
| `@Retry("name")` | `resilient.retry.<name>` |
| `@CircuitBreaker("name")` | `resilient.circuitbreaker.<name>` |
| `@Timeout("name")` | `resilient.timeout.<name>` |
| `@Fallback("name")` | `resilient.fallback.<name>` |
| `@Cacheable("name")` | `cache.caffeine.<name>` / `cache.redis.<name>` |
| `@HttpClient(configPath="path")` | `httpClient.<path>` |
| `@KafkaListener("path")` | `<path>` |
| `@ScheduleAtFixedRate(config="path")` | `scheduling.<path>` |

**Code Completion**
- Config keys in YAML based on `@ConfigSource` and annotation config paths

**Inspections**
- **Missing Kora DI provider** — no matching provider for injection parameter (enabled by default)
- **Unknown Kora config key** — YAML key not referenced by `@ConfigSource` (disabled by default)

### Requirements

- IntelliJ IDEA 2025.3+
- Java 21+

### Installation

**Settings → Plugins → Marketplace** → search **"Kora"** → **Install**

Or install from [plugin page](https://plugins.jetbrains.com/plugin/30225-kora).

#### Build from Source
```bash
git clone https://github.com/dsudomoin/kora-intellij-plugin.git
cd kora-intellij-plugin
./gradlew buildPlugin
```
The plugin ZIP will be in `build/distributions/`.

---

## Русский

Плагин IntelliJ IDEA для [Kora Framework](https://kora-projects.github.io/kora-docs/) — навигация в стиле Spring, инспекции и code completion для Kora DI.

### Возможности

**DI-навигация**
- Навигация от параметров инъекции к провайдерам и обратно
- `@Component`, `@Repository`, фабричные методы в `@KoraApp` / `@Module` / `@KoraSubmodule` / `@Generated`
- Разрешение тегов: `@Tag`, мета-аннотации, `@Tag.Any`
- Wrapper-типы: `All<T>`, `ValueOf<T>`, сопоставление generic-параметров
- Java и Kotlin (K1/K2)

**Иконки в гуттере**
- Провайдеры (`@Component`, `@Repository`, фабричные методы) — переход к использованиям
- Параметры инъекции — переход к провайдерам
- `@ConfigSource` — переход к config-файлам

**Навигация по конфигам**
- `@ConfigSource("path")` ↔ ключи в `application.yml` / `application.conf` (HOCON)
- Значения аннотаций → config-ключи:

| Аннотация | Конфиг-путь |
|---|---|
| `@Retry("name")` | `resilient.retry.<name>` |
| `@CircuitBreaker("name")` | `resilient.circuitbreaker.<name>` |
| `@Timeout("name")` | `resilient.timeout.<name>` |
| `@Fallback("name")` | `resilient.fallback.<name>` |
| `@Cacheable("name")` | `cache.caffeine.<name>` / `cache.redis.<name>` |
| `@HttpClient(configPath="path")` | `httpClient.<path>` |
| `@KafkaListener("path")` | `<path>` |
| `@ScheduleAtFixedRate(config="path")` | `scheduling.<path>` |

**Code Completion**
- Config-ключи в YAML на основе `@ConfigSource` и аннотаций с конфиг-путями

**Инспекции**
- **Missing Kora DI provider** — нет провайдера для параметра инъекции (включена по умолчанию)
- **Unknown Kora config key** — YAML-ключ не связан с `@ConfigSource` (выключена по умолчанию)

### Требования

- IntelliJ IDEA 2025.3+
- Java 21+

### Установка

**Settings → Plugins → Marketplace** → поиск **"Kora"** → **Install**

Или установите со [страницы плагина](https://plugins.jetbrains.com/plugin/30225-kora).

#### Сборка из исходников
```bash
git clone https://github.com/dsudomoin/kora-intellij-plugin.git
cd kora-intellij-plugin
./gradlew buildPlugin
```
ZIP плагина будет в `build/distributions/`.

---

## License

MIT
