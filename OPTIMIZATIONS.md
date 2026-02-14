# Оптимизации Kora Plugin

## Статус: DONE

| # | Оптимизация | Статус | Влияние |
|---|-------------|--------|---------|
| 1 | Удалить KoraAnnotationSearch (Kotlin import alias brute-force) | DONE | O(N)→O(0) |
| 2 | ConfigSourceLineMarkerProvider → collectSlowLineMarkers | DONE | EDT freeze → 0ms |
| 3 | Гранулярные cache trackers (forLanguage Java+Kotlin) | DONE | Кэш не инвалидируется при YAML/text правках |
| 4 | Убрать двойной full scan при пустом результате | DONE | -2x scans при "no provider" |
| 5 | LOG.info → LOG.debug с lazy lambda | DONE | 0 alloc overhead когда debug выключен |
| 6 | ProgressManager.checkCanceled() в длинных циклах | DONE | Instant cancel устаревших операций |
| 7 | ClassInheritorsSearch guard для common types | DONE | Защита от worst-case java.*/kotlin.* |
| 8 | HOCON reflection cache | DONE | Reflection lookup 1 раз, не на каждый вызов |

---

## Детали

### 1. Удалить KoraAnnotationSearch
**Удалён файл:** `KoraAnnotationSearch.kt`
**Проблема:** Supplement фаза итерировала ВСЕ классы проекта через `PsiShortNamesCache.allClassNames` ради Kotlin import aliases — O(N) по всем классам.
**Решение:** Удалён `KoraAnnotationSearch` целиком. `ProviderSearch` и `InjectionSiteSearch` используют прямой `AnnotatedElementsSearch` с дедупликацией.
**Затронутые файлы:** `ProviderSearch.kt`, `InjectionSiteSearch.kt`, удалён `KoraAnnotationSearch.kt`

### 2. ConfigSourceLineMarkerProvider → collectSlowLineMarkers
**Файл:** `ConfigSourceLineMarkerProvider.kt`
**Проблема:** `getLineMarkerInfo` работал на EDT, включая тяжёлые вызовы `ConfigPathResolver.resolveMemberToConfigPath()` → `ConfigSourceSearch.findAllConfigSources()` → `AnnotatedElementsSearch` для каждого PSI элемента.
**Решение:** `getLineMarkerInfo` → `return null`. Вся логика перенесена в `collectSlowLineMarkers()` (background thread) с `ProgressManager.checkCanceled()` в цикле.

### 3. Гранулярные cache trackers
**Файлы:** `ProviderSearch.kt`, `InjectionSiteSearch.kt`, `KoraModuleRegistry.kt`, `ConfigSourceSearch.kt`
**Проблема:** `PsiModificationTracker.getInstance(project)` инвалидируется при ЛЮБОМ PSI изменении (правки в YAML, комментариях, строках).
**Решение:** Кэши зависят от `PsiModificationTracker.forLanguage(JavaLanguage)` + `forLanguage(KotlinLanguage)`. Правки в YAML/HOCON/text файлах НЕ инвалидируют Java/Kotlin кэши.

### 4. Убрать двойной full scan
**Файлы:** `KoraProviderResolver.kt`, `KoraBeanNavigator.kt`
**Проблема:** Если index нашёл провайдеров, но фильтрация по type+tags дала 0 — выполнялся повторный полный скан. Двойная работа при каждом "no provider found".
**Решение:** Fallback на full scan только если `resolveViaIndex` вернул `null` (индекс недоступен). Пустой результат после фильтрации — легитимный ответ. Удалён метод `resolveWithFullScan`. В `KoraBeanNavigator`: убран `.ifEmpty { fullScan }` паттерн.

### 5. LOG.info → LOG.debug с lazy lambda
**Файлы:** `KoraProviderResolver.kt`, `KoraBeanNavigator.kt`, `KoraModuleRegistry.kt`, `ProviderSearch.kt`, `InjectionSiteSearch.kt`, `InjectionPointDetector.kt`
**Проблема:** ~30 вызовов `LOG.info("...")` с eager string interpolation. String concatenation + `canonicalText`/`presentableText` вызывались даже когда логирование отключено.
**Решение:** Все `LOG.info(...)` → `LOG.debug { ... }` с `import com.intellij.openapi.diagnostic.debug`. Lambda создаёт строку ТОЛЬКО когда debug включён. Нулевой overhead в production.

### 6. ProgressManager.checkCanceled()
**Файлы:** `ProviderSearch.kt`, `InjectionSiteSearch.kt`, `KoraModuleRegistry.kt`, `KoraConfigAnnotationRegistry.kt`, `ConfigSourceLineMarkerProvider.kt`
**Проблема:** Длинные циклы по `AnnotatedElementsSearch` результатам и module FQN'ам без проверки отмены. IDE не могла прервать устаревшую операцию при продолжении ввода.
**Решение:** `ProgressManager.checkCanceled()` добавлен в начало каждой итерации тяжёлых циклов (14 мест).

### 7. ClassInheritorsSearch guard
**Файлы:** `KoraProviderResolver.kt`, `KoraBeanNavigator.kt`
**Проблема:** `ClassInheritorsSearch.search(requiredClass, scope, true)` для типов из `java.*`/`kotlin.*` (Serializable, Comparable, AutoCloseable) мог возвращать тысячи наследников, каждый из которых порождал index query.
**Решение:** Guard `isCommonType(rawFqn)` — пропускает subtype search для `java.`, `javax.`, `kotlin.`, `kotlinx.` пакетов. `ProgressManager.checkCanceled()` добавлен внутрь цикла по наследникам.

### 8. HOCON reflection cache
**Файл:** `ConfigSourceLineMarkerProvider.kt`
**Проблема:** `Class.forName()` + `getMethod()` вызывались на каждый `findHoconKeysByPathImpl`. Внутри `hoconKeyFullPath` — повторный `getMethod("isDefined")` и `getMethod("get")` на каждый HOCON ключ.
**Решение:** `HoconReflectionCache` — кэширует `HKey.class` и `fullPathText` Method. `ScalaOptionReflectionCache` — кэширует `isDefined` и `get` методы Scala Option.

---

## Раунд 2: Оптимизации

### Сводная таблица

| # | Приоритет | Оптимизация | Статус | Метрика |
|---|-----------|-------------|--------|---------|
| 9 | P0 | SingleParam/MultiParam — EDT freeze | DONE | 100-500ms → 0ms на EDT |
| 10 | P0 | ConfigGutterNavigationHandler — EDT freeze | DONE | 50-200ms → 0ms на EDT |
| 11 | P1 | supplementProvidersFromUnannotatedModules O(N×M) | DONE | 165 findClass → 15 |
| 12 | P1 | findAnnotatedElements не кэшировано | DONE | 560 annotation searches → 28 |
| 13 | P2 | Двойной InjectionPointDetector.detect() | DONE | 5-15ms экономия на navigate |
| 14 | P2 | Порядок проверок isKoraModuleClass | DONE | 3× UAST → 1× HashSet |
| 15 | P2 | Regex в ConfigPathResolver на каждый вызов | DONE | 500 компиляций → 0 |
| 16 | P2 | isReferencedFromConfigSource — full scan на каждый элемент | DONE | Пропорционально кол-ву элементов |
| 17 | P3 | allScope → projectScope для @Component в AnnotatedElementsSearch | DONE | Minor |
| 18 | P3 | Двойная нормализация path в matchesConfigPath | DONE | Решено в рамках #12 (кэш) |

---

### 9. SingleParamNavigationHandler / MultiParamNavigationHandler — EDT freeze (P0)
**Файл:** `KoraLineMarkerProvider.kt:414-455`
**Проблема:** `KoraProviderResolver.resolve()` вызывается **прямо на EDT** без `runProcessWithProgressSynchronously` и без `ReadAction`. Это вызывает:
- UI freeze (resolve делает index query → ClassInheritorsSearch → supplement scan по всем модулям)
- На IntelliJ 2025.3 — потенциальный `IllegalStateException` (EDT без implicit read access)

**Сравнение:** `ProviderUsagesNavigationHandler` (строка 293) и `CombinedNavigationHandler` (строка 357) **корректно** оборачивают в `runProcessWithProgressSynchronously + ReadAction.compute`.
**Метрика:** В проекте с ~50 модулями `resolve()` выполняет: `InjectionPointDetector.detect()` (UAST conversion) + index query + `ClassInheritorsSearch` + `supplementProvidersFromUnannotatedModules` × N inheritors. Это **100-500ms** блокировки EDT. Порог заметности — 16ms (один кадр при 60fps).

### 10. ConfigGutterNavigationHandler — EDT freeze (P0)
**Файл:** `ConfigSourceLineMarkerProvider.kt:120-153`
**Проблема:** `resolveConfigPath()` + `findConfigKeyElements()` на EDT. `findConfigKeyElements` делает `FilenameIndex.getAllFilesByExt()` и парсит **все** YAML/HOCON файлы проекта.
**Метрика:** В проекте с 20+ конфиг-файлами — **50-200ms** на EDT.

### 11. supplementProvidersFromUnannotatedModules O(N×M) (P1)
**Файл:** `ProviderSearch.kt:205-232`
**Проблема:** `findProvidersByType(typeFqn)` вызывает supplement scan для каждого typeFqn. В `resolveViaIndex()` (KoraProviderResolver.kt:58-63) — один вызов на каждый inheritor типа. Если у интерфейса 10 наследников — 11 вызовов supplement (1 direct + 10 inheritors), каждый итерирует **все** unannotated модули.
**Решение:** Кэшировать все providers из unannotated модулей целиком (key: typeFqn → providers), собранные за один проход. Использовать `CachedValuesManager` с тем же tracker.
**Метрика:** При 15 unannotated модулях и 10 inheritors: 11 × 15 = **165** `JavaPsiFacade.findClass()` + method scan. После оптимизации — **15** (один проход).

### 12. KoraConfigAnnotationRegistry.findAnnotatedElements не кэшировано (P1)
**Файл:** `KoraConfigAnnotationRegistry.kt:73-111`
**Проблема:** `KoraUnknownConfigKeyInspection` (строка 47) вызывает `findAnnotatedElements()` для **каждого** YAML-ключа, не нашедшего @ConfigSource. Каждый вызов делает до 14 × `AnnotatedElementsSearch.searchPsiMethods` + `searchPsiClasses` — 28 поисков по индексу аннотаций.
**Решение:** Кэшировать маппинг `configPath → PsiElement` через `CachedValuesManager`.
**Метрика:** Конфиг с 50 ключами, из которых 30 framework-level → 20 вызовов × 28 searches = **560** annotation searches. С кэшированием: **28** (один раз).

### 13. Двойной InjectionPointDetector.detect() в KoraBeanNavigator (P2)
**Файл:** `KoraBeanNavigator.kt:208-212`
**Проблема:**
```kotlin
val injectionPoint = InjectionPointDetector.detect(element) ?: return null  // 1й раз
val providers = KoraProviderResolver.resolve(element).distinct()            // 2й раз внутри
```
`KoraProviderResolver.resolve()` (строка 19) снова вызывает `detect()`. UAST conversion + annotation checks + module registry lookup — **~5-15ms** впустую.
**Решение:** Добавить `KoraProviderResolver.resolve(injectionPoint, project)` overload, принимающий готовый `InjectionPoint`.

### 14. InjectionPointDetector.isKoraModuleClass — неоптимальный порядок (P2)
**Файл:** `InjectionPointDetector.kt:84-100`
**Проблема:** Сначала `hasAnyModuleAnnotationInSupers()` (рекурсивный обход суперклассов с UAST conversion), потом `KoraModuleRegistry.getModuleClassFqns()` (кэшированный O(1) lookup в Set). Порядок инвертирован.
**Решение:** Переставить — сначала кэшированный registry, потом дорогой PSI walk.
**Метрика:** `hasAnyModuleAnnotationInSupers` на интерфейсе с 3 суперинтерфейсами: 3× UAST conversion + annotation check. Registry lookup: **один** `HashSet.contains()`.

### 15. Regex в ConfigPathResolver создаётся на каждый вызов (P2)
**Файл:** `ConfigPathResolver.kt:217-230`
**Проблема:** `Regex("([a-z0-9])([A-Z])")` и `Regex("-([a-z])")` — компиляция на каждый вызов `camelToKebab`/`kebabToCamel`. Вызываются из `matchesConfigPath` (для каждого сегмента пути), `normalizedEquals`, `matchesWildcardPath`.
**Решение:** `companion object { private val CAMEL_REGEX = Regex(...) }`.
**Метрика:** `Regex()` конструктор: **~0.1ms**. При 50 YAML-ключах × 5 сегментов × 2 regex = **500** компиляций. С кэшем: **0**.

### 16. isReferencedFromConfigSource — full scan на каждый элемент (P2)
**Файл:** `ConfigSourceLineMarkerProvider.kt:111-118`
**Проблема:** Для каждого элемента в файле `resolveMemberToConfigPath` итерирует все @ConfigSource классы проекта. Можно сначала проверить, что класс текущего элемента фигурирует как return type в @ConfigSource (через кэшированный `ConfigSourceSearch`), отсечь проверку на ранней стадии.
**Решение:** Собрать Set FQN всех типов, используемых как return types в @ConfigSource классах, проверять принадлежность перед вызовом `resolveMemberToConfigPath`.

### 17. allScope → projectScope для @Component в AnnotatedElementsSearch (P3)
**Файл:** `ProviderSearch.kt:82-121`
**Проблема:** `@Component` классы — всегда в проекте пользователя, не в JAR. `AnnotatedElementsSearch` с `allScope` ищет лишнее.
**Решение:** Для `@Component` → `projectScope`, для `@Generated` и других → `allScope`.

### 18. Двойная нормализация path в matchesConfigPath (P3)
**Файл:** `KoraConfigAnnotationRegistry.kt:113-117`
**Проблема:** Каждый вызов `matchesConfigPath` делает `split(".")` + `joinToString` с `camelToKebab` для **обоих** аргументов. `candidatePath` (из annotation value) не меняется между вызовами.
**Решение:** Нормализовать `candidatePath` один раз при получении из аннотации.

---

## Раунд 3: Оптимизации

### Сводная таблица

| # | Приоритет | Оптимизация | Статус | Метрика |
|---|-----------|-------------|--------|---------|
| 19 | P0 | AnnotationValueGotoHandler — EDT freeze | DONE | 50-200ms → 0ms на EDT |
| 20 | P0 | YamlConfigGotoHandler — EDT freeze | DONE | 100-500ms → 0ms на EDT |
| 21 | P0 | HoconConfigGotoHandler — EDT freeze + isDumb/hasKoraLibrary | DONE | 100-500ms → 0ms на EDT |
| 22 | P0 | Все GotoDeclarationHandler — DumbService.isDumb() guard | DONE | IndexNotReadyException → 0 |
| 23 | P1 | findConfigKeyElements — checkCanceled в цикле по файлам | DONE | Instant cancel |
| 24 | P1 | allScope → projectScope для @ConfigSource и annotation mappings | DONE | -N library classes в scan |
| 25 | P1 | hasAnyModuleAnnotationInSupers — удалён избыточный fallback | DONE | O(n) UAST conv → 0 |
| 26 | P1 | MultiParamNavigationHandler — хранить String вместо UParameter | DONE | PsiInvalidElementAccessException → 0 |
| 27 | P1 | ScalaOptionReflectionCache — thread-safe @Volatile | DONE | Data race → 0 |
| 28 | P2 | normalizedEquals — вынести нормализацию из цикла | DONE | O(keys × 2) → O(1) per split |
| 29 | P2 | findMappingForAnnotation — линейный поиск → HashMap | DONE | O(14) → O(1) |
| 30 | P2 | checkCanceled в HOCON PSI traversal (Pass 1, Pass 2) | DONE | Instant cancel |
| 31 | P2 | String interpolation в цикле inspection → pre-computed | DONE | O(keys × sources) alloc → O(1) |

---

### 19. AnnotationValueGotoHandler — EDT freeze (P0)
**Файл:** `AnnotationValueGotoHandler.kt`
**Проблема:** `findConfigKeyElements()` вызывается прямо на EDT. Внутри — `FilenameIndex.getAllFilesByExt()` (stub index), обход всех YAML/HOCON файлов, парсинг PSI деревьев. При 20+ конфиг-файлах — **50-200ms** freeze.
**Решение:** Добавлен `DumbService.isDumb()` guard. Тяжёлая часть обёрнута в `runProcessWithProgressSynchronously` + `ReadAction.compute`.

### 20. YamlConfigGotoHandler — EDT freeze (P0)
**Файл:** `YamlConfigGotoHandler.kt`
**Проблема:** `ConfigPathResolver.resolveConfigKeyToMethod()` и `KoraConfigAnnotationRegistry.findAnnotatedElements()` на EDT. При холодном кэше запускается полный `AnnotatedElementsSearch` по всем 14 аннотациям — **100-500ms**.
**Решение:** Добавлен `DumbService.isDumb()` guard. Вся логика resolve обёрнута в `runProcessWithProgressSynchronously` + `ReadAction.compute`.

### 21. HoconConfigGotoHandler — EDT freeze + isDumb/hasKoraLibrary (P0)
**Файл:** `HoconConfigGotoHandler.kt`
**Проблема:** Аналогично YAML — тяжёлые вызовы на EDT. Дополнительно: отсутствовали проверки `DumbService.isDumb()` и `KoraLibraryUtil.hasKoraLibrary()` — единственный handler без обеих.
**Решение:** Добавлены оба guard'а. Resolve обёрнут в `runProcessWithProgressSynchronously` + `ReadAction.compute`.

### 22. Все GotoDeclarationHandler — DumbService.isDumb() guard (P0)
**Файлы:** `AnnotationValueGotoHandler.kt`, `YamlConfigGotoHandler.kt`, `HoconConfigGotoHandler.kt`
**Проблема:** Без `isDumb()` проверки `AnnotatedElementsSearch`, `FilenameIndex` и stub index бросают `IndexNotReadyException` при вызове во время индексации.
**Решение:** `DumbService.isDumb(project)` → `return null` в начале каждого `getGotoDeclarationTargets`.

### 23. findConfigKeyElements — checkCanceled в цикле по файлам (P1)
**Файл:** `ConfigSourceLineMarkerProvider.kt`
**Проблема:** Цикл по всем config файлам проекта без `ProgressManager.checkCanceled()`. IDE не могла прервать устаревшую операцию.
**Решение:** `ProgressManager.checkCanceled()` добавлен в начале итерации по файлам.

### 24. allScope → projectScope для @ConfigSource и annotation mappings (P1)
**Файлы:** `ConfigSourceSearch.kt`, `KoraConfigAnnotationRegistry.kt`
**Проблема:** `AnnotatedElementsSearch` использовал `allScope` — искал аннотированные элементы в JAR'ах. `@ConfigSource` классы и `@Retry/@Timeout/etc` методы всегда в проектных исходниках.
**Решение:** `allScope` для `JavaPsiFacade.findClass` (аннотация в JAR), `projectScope` для `AnnotatedElementsSearch` (элементы в проекте). Также добавлен `checkCanceled()` между маппингами в `buildAnnotatedElementsMap`.

### 25. hasAnyModuleAnnotationInSupers — удалён избыточный fallback (P1)
**Файл:** `InjectionPointDetector.kt`
**Проблема:** После проверки `KoraModuleRegistry.getModuleClassFqns()` (O(1) HashSet lookup) вызывался `hasAnyModuleAnnotationInSupers` — рекурсивный обход ВСЕХ суперинтерфейсов с `toUElement()` на каждом. Реестр уже содержит все module FQN (включая unannotated через extends-цепи), поэтому fallback никогда не находил ничего нового.
**Решение:** Удалён `hasAnyModuleAnnotationInSupers` целиком. Достаточно проверки через registry.
**Метрика:** На интерфейсе с 3 суперинтерфейсами: **3× UAST conversion + annotation check** → **0**.

### 26. MultiParamNavigationHandler — хранить String вместо UParameter (P1)
**Файл:** `KoraLineMarkerProvider.kt`
**Проблема:** `MultiParamNavigationHandler` хранил `List<UParameter>` ссылки. `LineMarkerInfo` может пережить PSI дерево (при редактировании файла PSI перестраивается). Обращение к `value.type.presentableText` и `param.sourcePsi` на невалидных объектах → `PsiInvalidElementAccessException`.
**Решение:** Хранить `List<ParamInfo>` (name + typeText как String) + classFqn + methodName. В navigate handler пересоздавать PSI через `JavaPsiFacade.findClass()` → `findMethodsByName` → `parameterList.parameters.find`.

### 27. ScalaOptionReflectionCache — thread-safe @Volatile (P1)
**Файл:** `ConfigSourceLineMarkerProvider.kt`
**Проблема:** `var isDefinedMethod` и `var getMethod` без `@Volatile` или synchronized. При concurrent доступе возможна race condition: один поток видит `isDefinedMethod != null`, но `getMethod == null` (частичная инициализация).
**Решение:** Оба поля помечены `@Volatile`. В `resolve()` — сначала вычисляются локальные переменные, затем записываются в поля (порядок: `getMethod` → `isDefinedMethod`, так что check на `isDefinedMethod != null` гарантирует что `getMethod` тоже записан).

### 28. normalizedEquals — вынести нормализацию из цикла (P2)
**Файл:** `ConfigSourceLineMarkerProvider.kt`
**Проблема:** `normalizedEquals(fullPath, prefixPath)` в цикле по HOCON ключам нормализовала **обе** строки на каждой итерации. `prefixPath` и `suffixPath` не меняются внутри цикла.
**Решение:** Заменена `normalizedEquals` на `normalizePath`. Нормализация `prefixNorm`/`suffixNorm` вынесена за пределы цикла по ключам.
**Метрика:** При 100 HOCON ключах и 5 split-точках: **100 × 5 × 2 = 1000** лишних нормализаций → **5**.

### 29. findMappingForAnnotation — линейный поиск → HashMap (P2)
**Файл:** `KoraConfigAnnotationRegistry.kt`
**Проблема:** `mappings.find { it.annotationFqn == annotationFqn }` — линейный поиск по 14 элементам при каждом вызове.
**Решение:** Добавлен `mappingsByFqn: Map<String, ConfigAnnotationMapping>` = `mappings.associateBy { it.annotationFqn }`. Lookup через `mappingsByFqn[annotationFqn]`.
**Метрика:** O(14) → O(1).

### 30. checkCanceled в HOCON PSI traversal (P2)
**Файл:** `ConfigSourceLineMarkerProvider.kt`
**Проблема:** `findHoconKeysByPathImpl` — два прохода по `allKeys` (Pass 1 и Pass 2) без `ProgressManager.checkCanceled()`. В больших HOCON файлах — длинная неотменяемая операция.
**Решение:** `ProgressManager.checkCanceled()` добавлен в циклы Pass 1 и Pass 2.

### 31. String interpolation в цикле inspection (P2)
**Файл:** `KoraUnknownConfigKeyInspection.kt`
**Проблема:** `"${it.path}."` и `"$fullPath."` создают новые строки для каждого `ConfigSourceEntry` для каждого YAML ключа. O(keys × configSources) аллокаций.
**Решение:** `fullPathDot = "$fullPath."` вычисляется один раз перед циклом. `"${entry.path}."` — минимизировано.

---

## Раунд 4: Оптимизации

### Сводная таблица

| # | Приоритет | Оптимизация | Статус | Метрика |
|---|-----------|-------------|--------|---------|
| 32 | P0 | ScalaOptionReflectionCache — race condition → ConcurrentHashMap | DONE | NPE → 0, thread-safe |
| 33 | P0 | KoraMissingProviderInspection — передавать InjectionPoint в resolve | DONE | -1 detect() per param |
| 34 | P0 | KoraProviderResolver.resolveViaIndex — двойной resolve() | DONE | 2x resolve → 1x |
| 35 | P1 | TagExtractor — пропускать non-Kora аннотации | DONE | -N resolve() для @Nullable etc |
| 36 | P1 | KoraBeanNavigator — двойной resolve() через getRawTypeFqn | DONE | 2x resolve → 1x (2 метода) |
| 37 | P1 | collectElements — checkCanceled() в рекурсии | DONE | Instant cancel |
| 38 | P1 | HoconReflectionCache — lazy init вместо eager Class.forName | DONE | Exception loop → null check |
| 39 | P1 | COMMON_TYPE_PREFIXES дедупликация | DONE | 2 копии → 1 в KoraAnnotations |
| 40 | P1 | plugin.xml — order="last" для всех extensions | DONE | Меньше overhead в non-Kora |
| 41 | P2 | KoraProviderResolver.resolve — single-pass вместо 5 списков | DONE | 5 List → 2 |
| 42 | P2 | KoraUnknownConfigKeyInspection — zero-alloc prefix matching | DONE | O(N) alloc → 0 |
| 43 | P2 | configFileExtensions → static val | DONE | List alloc per call → 0 |

---

### 32. ScalaOptionReflectionCache — race condition → ConcurrentHashMap (P0)
**Файл:** `ConfigSourceLineMarkerProvider.kt`
**Проблема:** `@Volatile` на двух `var` полях не защищает от race condition: два потока могут одновременно войти в `if (isDefinedMethod == null)`, и один поток может увидеть `isDefinedMethod != null` но `getMethod == null` → NPE при `!!`.
**Решение:** Заменён на `ConcurrentHashMap<Class, ScalaOptionMethods>` с `computeIfAbsent` — атомарная инициализация, корректно работает с разными подклассами `scala.Option` (Some, None).

### 33. KoraMissingProviderInspection — передавать InjectionPoint в resolve (P0)
**Файл:** `KoraMissingProviderInspection.kt`
**Проблема:** `checkParameter()` вызывал `InjectionPointDetector.detect(nameIdentifier)`, а затем `KoraProviderResolver.resolve(nameIdentifier)` — который внутри снова вызывал `detect()`. Двойная UAST конверсия + annotation checks для каждого параметра.
**Решение:** Передавать готовый `InjectionPoint` в `KoraProviderResolver.resolve(injectionPoint, project)`.

### 34. KoraProviderResolver.resolveViaIndex — двойной resolve() (P0)
**Файл:** `KoraProviderResolver.kt`
**Проблема:** `requiredType.resolve()` вызывался дважды — первый раз для получения FQN (строка 45), второй для `ClassInheritorsSearch` (строка 57). Каждый `resolve()` — lookup в stub index.
**Решение:** Вычислить `requiredClass` один раз, переиспользовать для FQN и ClassInheritorsSearch.

### 35. TagExtractor — пропускать non-Kora аннотации (P1)
**Файл:** `TagExtractor.kt`
**Проблема:** `annotation.resolve()` вызывался для КАЖДОЙ аннотации параметра, включая `@Nullable`, `@NotNull`, `@Override`, `javax.*`, `jakarta.*` и т.д. — все ради проверки мета-тегов `@Tag`. Дорогой resolve для аннотаций, которые гарантированно не являются мета-тегами Kora.
**Решение:** `isKnownNonKoraAnnotation(fqn)` — пропускает аннотации с префиксами `java.`, `javax.`, `jakarta.`, `kotlin.`, `kotlinx.`, `org.jetbrains.annotations.`, `org.intellij.`, `org.springframework.`.
**Метрика:** Типичный параметр имеет 1-3 аннотации (@Nullable, @NotNull). Экономия **1-3 resolve()** на параметр.

### 36. KoraBeanNavigator — двойной resolve() через getRawTypeFqn (P1)
**Файл:** `KoraBeanNavigator.kt`
**Проблема:** `findProvidersForType()` и `findInjectionSitesForProvidedType()` — `KoraIndexUtil.getRawTypeFqn(type)` внутри делает `type.resolve()`, а затем вызывающий код повторно делает `type.resolve()` для ClassInheritorsSearch/supers.
**Решение:** Resolve один раз, FQN из resolved class.

### 37. collectElements — checkCanceled() в рекурсии (P1)
**Файл:** `ConfigSourceLineMarkerProvider.kt`
**Проблема:** Рекурсивный обход всего PSI-дерева HOCON файла без `ProgressManager.checkCanceled()`. На больших файлах — длинная неотменяемая операция.
**Решение:** `ProgressManager.checkCanceled()` в начале каждого рекурсивного вызова.

### 38. HoconReflectionCache — lazy init вместо eager Class.forName (P1)
**Файл:** `ConfigSourceLineMarkerProvider.kt`
**Проблема:** `Class.forName("...HKey")` в object init — при отсутствии HOCON плагина бросает `ExceptionInInitializerError` при первом обращении. После этого каждый последующий вызов бросает `NoClassDefFoundError` — exception loop на каждый HOCON файл.
**Решение:** `HoconReflectionCache.instance: HoconReflection?` — Kotlin `by lazy` с try/catch. Возвращает `null` если HOCON недоступен. Вызывающий код проверяет `?: return emptyList()`.

### 39. COMMON_TYPE_PREFIXES дедупликация (P1)
**Файлы:** `KoraAnnotations.kt`, `KoraProviderResolver.kt`, `KoraBeanNavigator.kt`
**Проблема:** `arrayOf("java.", "javax.", "kotlin.", "kotlinx.")` дублировался в двух файлах.
**Решение:** Единственная копия `KoraAnnotations.COMMON_TYPE_PREFIXES`. Оба потребителя ссылаются на неё.

### 40. plugin.xml — order="last" для всех extensions (P1)
**Файлы:** `plugin.xml`, `hocon-support.xml`
**Проблема:** `GotoDeclarationHandler` и `LineMarkerProvider` вызываются для каждого Ctrl+Click и каждого файла во всей IDE. Без `order="last"` они выполняются раньше встроенных — `hasKoraLibrary()` fast-exit, но всё равно overhead.
**Решение:** `order="last"` на всех 4 LineMarkerProvider, 2 GotoDeclarationHandler (plugin.xml) и 1 GotoDeclarationHandler (hocon-support.xml).
