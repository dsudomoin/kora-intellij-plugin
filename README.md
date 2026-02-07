# Kora IntelliJ Plugin

[Русский](#русский) | [English](#english)

---

## English

IntelliJ IDEA plugin for the [Kora Framework](https://kora-projects.github.io/kora-docs/) — a JVM dependency injection framework without runtime reflection.

Provides Spring-like IDE navigation: jump from injection points directly to their providers.

### Features

**Go to Declaration**
- Navigate from constructor parameters and factory method parameters to the component that provides them
- Supports `@Component` classes, factory methods in `@KoraApp`, `@Module`, `@KoraSubmodule` interfaces
- Resolves tag-based qualification: `@Tag`, custom meta-annotations, `@Tag.Any`
- Works with both Java and Kotlin (K1/K2) via UAST
- Multiple candidates — shows a chooser popup

**Gutter Icons**
- Line markers on providers (classes, factory methods) for quick navigation
- Line markers on `@ConfigSource` annotations linking to YAML/HOCON config files

**Config Navigation**
- Navigate from `@ConfigSource("path")` to the corresponding key in `application.yml` or `application.conf`
- Navigate from YAML/HOCON config keys back to `@ConfigSource` usages
- Supports HOCON via optional plugin dependency

### Requirements

- IntelliJ IDEA 2025.3+ (build 253+)
- Java 21+

### Installation

#### From Disk
1. Download the latest release ZIP from [Releases](https://github.com/dsudomoin/kora-intellij-plugin/releases)
2. In IDEA: **Settings → Plugins → ⚙️ → Install Plugin from Disk...**
3. Select the ZIP file and restart the IDE

#### Build from Source
```bash
git clone https://github.com/dsudomoin/kora-intellij-plugin.git
cd kora-intellij-plugin
./gradlew buildPlugin
```
The plugin ZIP will be in `build/distributions/`.

### Development

```bash
./gradlew build       # Compile + tests
./gradlew test        # Tests only
./gradlew runIde      # Launch sandbox IDE with the plugin
```

### Supported Kora Annotations

| Annotation | Role |
|---|---|
| `@Component` | Marks a class as a DI component (auto-factory) |
| `@KoraApp` | Main application interface with factory methods |
| `@Module` | Internal module interface with factory methods |
| `@KoraSubmodule` | Interface with generated aggregating module |
| `@Tag` | Tag-based qualifier for disambiguation |
| `@ConfigSource` | Binds a config class to a config file path |

---

## Русский

Плагин IntelliJ IDEA для [Kora Framework](https://kora-projects.github.io/kora-docs/) — JVM-фреймворка внедрения зависимостей без рефлексии в рантайме.

Обеспечивает навигацию в стиле Spring: переход от точек инъекции напрямую к провайдерам компонентов.

### Возможности

**Go to Declaration**
- Навигация от параметров конструктора и фабричных методов к компоненту-провайдеру
- Поддержка `@Component` классов, фабричных методов в интерфейсах `@KoraApp`, `@Module`, `@KoraSubmodule`
- Разрешение тегов: `@Tag`, кастомные мета-аннотации, `@Tag.Any`
- Работает с Java и Kotlin (K1/K2) через UAST
- При нескольких кандидатах — всплывающее окно выбора

**Иконки в гуттере**
- Маркеры на провайдерах (классах, фабричных методах) для быстрой навигации
- Маркеры на аннотациях `@ConfigSource` со ссылкой на конфиг-файлы YAML/HOCON

**Навигация по конфигам**
- Переход из `@ConfigSource("path")` к соответствующему ключу в `application.yml` или `application.conf`
- Обратная навигация из ключей YAML/HOCON к использованиям `@ConfigSource`
- Поддержка HOCON через опциональную зависимость

### Требования

- IntelliJ IDEA 2025.3+ (сборка 253+)
- Java 21+

### Установка

#### С диска
1. Скачайте ZIP последнего релиза из [Releases](https://github.com/dsudomoin/kora-intellij-plugin/releases)
2. В IDEA: **Settings → Plugins → ⚙️ → Install Plugin from Disk...**
3. Выберите ZIP-файл и перезапустите IDE

#### Сборка из исходников
```bash
git clone https://github.com/dsudomoin/kora-intellij-plugin.git
cd kora-intellij-plugin
./gradlew buildPlugin
```
ZIP плагина будет в `build/distributions/`.

### Разработка

```bash
./gradlew build       # Компиляция + тесты
./gradlew test        # Только тесты
./gradlew runIde      # Запуск песочницы IDE с плагином
```

### Поддерживаемые аннотации Kora

| Аннотация | Назначение |
|---|---|
| `@Component` | Помечает класс как DI-компонент (автоматическая фабрика) |
| `@KoraApp` | Главный интерфейс приложения с фабричными методами |
| `@Module` | Внутренний модульный интерфейс с фабричными методами |
| `@KoraSubmodule` | Интерфейс с генерируемым агрегирующим модулем |
| `@Tag` | Тег-квалификатор для разрешения неоднозначностей |
| `@ConfigSource` | Привязка конфиг-класса к пути в конфиг-файле |

---

## License

Apache License 2.0
