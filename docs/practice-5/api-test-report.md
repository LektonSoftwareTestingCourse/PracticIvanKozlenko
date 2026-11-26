# Практика 5. API-тестирование СМП

## 1. Покрытие

| Методы | Проверки | Классы |
|---|---|---|
| GET /health | 200, схема, типы, обязательные поля, Content-Type, 503 при отключенном Switch | GatewayContractTest, DownstreamFailureTest |
| POST /api/transactions | Одобрение, формат RRN/authCode, processingTimeMs, бизнес-отказы с HTTP 200, валидация 400, 429 и Retry-After, 503, компенсация при сбое брокера | AuthorizationContractTest, PaymentValidationTest, RateLimitTest, DownstreamFailureTest, BrokerRollbackTest |
| POST /api/cards | Создание 201, обязательные поля, длины/алфавит BIN и валюты, имя, отрицательные лимиты 400 | CardsContractTest, BoundaryContractTest |
| POST /api/cards/generate | 201, count 0/−1 и пустые bins → 400 | CardsContractTest, BoundaryContractTest |
| GET/PATCH/DELETE /api/cards/{pan} | 200/200/204, неизвестная карта 404, формат PAN 400, отрицательные лимиты PATCH 400 | CardsContractTest, BoundaryContractTest |
| GET /api/cards | Фильтры, одиночные и парные несовпадения, пустая выборка, смещение, лимиты 1/10000/10001, ошибки параметров | CardsContractTest, BoundaryContractTest |
| POST /api/cards/bulk-update | Обновление своей карты 200, отсутствие status 400 | CardsContractTest, BoundaryContractTest |
| POST /api/cards/{pan}/reserve, /rollback | Баланс до/после, 402, повтор 409, отсутствующая карта 404, неположительная сумма 400 | CardsContractTest, BoundaryContractTest |
| POST /api/internal/authorize | Одобрение, доменные отказы, входная валидация, накопление дневного/месячного лимита, повторный STAN | AuthorizationContractTest, AuthorizationValidationTest, CardServiceFailureTest |
| POST /api/internal/rollback | 200, повтор 409, отсутствующий резерв 404, обязательные поля 400, недоступность Cards 503 | AuthorizationContractTest, BoundaryContractTest, CardServiceFailureTest |
| POST /api/internal/route, GET /api/bin/{bin} | Неизвестный BIN, известный BIN, 404, диагностический 500 | AuthorizationContractTest, GatewayContractTest |
| POST /api/internal/log | 201/200/409, валидация 400, типы UUID/enum/date | LoggerContractTest |
| GET /api/transactions/search | Восемь фильтров, комбинации шести селекторов, границы дат/limit/offset, пустой результат, неправильный диапазон | SearchContractTest |
| GET /api/transactions/export | Content-Type, имя файла, своя запись и отсутствие чужого merchantId, неправильный диапазон 400 | SearchContractTest, BoundaryContractTest |
| GET /api/dashboard/stats, /recent, /charts | Счетчики, границы recent.limit, hour/day, неправильная гранулярность и диапазон | GatewayContractTest, BoundaryContractTest |
| POST /api/simulator/terminal/run, /start-continuous, /stop | Запуск, остановка, count=0, tps=0, отсутствующие документированные start/status | GatewayContractTest, BoundaryContractTest |
| POST /api/simulator/merchant/run, /fee, GET /api/simulator/merchants | Запуск, count/tps, комиссия существующей операции и 404 для неизвестной, справочник напрямую и 404 через Gateway | GatewayContractTest, BoundaryContractTest |

## 2. Бизнес-отказы

| Причина | Код | Подготовка |
|---|---|---|
| CARD_NOT_FOUND | 14 | Неизвестный PAN у Authorization, неизвестный BIN у Switch |
| CARD_BLOCKED / CARD_INACTIVE | 05 | PATCH статуса созданной карты, также проверяется внешний Gateway |
| CARD_EXPIRED | 54 | Статус EXPIRED и дата запроса после срока действия |
| INSUFFICIENT_FUNDS | 51 | Сумма выше баланса либо уменьшение баланса перед запросом |
| EXCEEDS_AMOUNT_LIMIT | 61 | Сумма выше лимита, уменьшение лимита, точная граница накопленного дневного/месячного лимита и следующий платеж |
| CARD_NOT_FOUND после удаления | 14 | DELETE скрывает карту через SQLRestriction, Authorization возвращает 404 |
| TRANSACTION_NOT_FOUND | 14 | Неизвестный RRN при существующей карте |
| ALREADY_ROLLED_BACK | 05 | Повтор отката |
| SERVICE_UNAVAILABLE | 96 | Остановлен Card Management, отдельно проверяется компенсация при остановке RabbitMQ |

## 3. Валидация

| Область | Проверяемые классы и границы |
|---|---|
| Платеж Gateway | Обязательные поля, PAN 15/16/17, MCC 3/4/5, неправильный алфавит, валюта 2/3/4, amount 0/−1, MTI |
| Authorization | Обязательные поля, длины n−1/n+1 и пробелы, отрицательная сумма, неправильная дата, обязательные поля отката |
| Cards | BIN и валюта: длина/алфавит, обязательность, имя владельца, отрицательные лимиты, генерация count 0/−1, пустые bins, reserve/rollback amount 0/−1 |
| Logger | Обязательные поля, точная длина и алфавит, превышение максимальной длины, нулевые/отрицательные суммы, UUID, enum, даты |
| Поиск | limit 0/1/500/501, offset −1/0/1, неправильный статус и дата, обратный диапазон |
| Dashboard | recent.limit −1/0/1/500/501, hour/day/week, обратный диапазон |
| Симуляторы | count=0, tps=0 |

Каждый негативный случай изменяет одно поле. Параметризация объединяет случаи с одинаковым алгоритмом проверки.

## 4. Расхождения контракта и реализации

| Декларация / требование | Наблюдаемое / основание | Классификация и решение |
|---|---|---|
| MTI ответа 0110 в docs/api-spec.md | Код AuthorizationResponse копирует 0100, отдельный HTTP-тест фиксирует результат | Несоответствие спецификации, текущий тест документирует существующее поведение, не объявляет его правильным |
| OpenAPI: terminal/start и terminal/status | Controller реализует start-continuous и stop, отсутствующие start/status напрямую в Terminal возвращают 500 с No static resource, прямой вызов исключает влияние circuit breaker Gateway | Устаревшие пути документации и дефект обработки отсутствующего ресурса (500 вместо 404), тест фиксирует фактическое поведение |
| GET /api/simulator/merchants в OpenAPI | Метод есть в Merchant, Gateway маршрутизирует только /merchant/** | Дефект маршрутизации/контракта, 200 напрямую, 404 снаружи |
| Пример terminal/run без tps | DTO требует tps >= 1 | Неполный пример документации, проверяется отказ tps=0 |
| Перечень фильтров в docs/api-spec.md | Код дополнительно поддерживает issuerId и mcc, stan не поддерживает | Неполная документация, используются рабочие фильтры и GPath |
| Повторная авторизация могла бы быть идемпотентной | Один STAN создает два резерва, проверяются разные RRN и двойное уменьшение баланса | Риск повторного списания исследовательский тест фиксирует поведение |
| Из ошибки должно следовать поле и правило | Authorization возвращает стандартные status/error/path без поля, Cards возвращает правило без имени поля | Ограничение диагностического контракта. Тесты не выдумывают отсутствующие поля, реализацию сервиса в этой работе не меняем |

Наблюдения подтверждаются только теми тестами, которые перечислены, статический анализ технических веток не считается HTTP-экспериментом.

## 5. Обоснование достаточности

Подготовка через API, уникальные карты и идентификаторы уменьшают зависимость от порядка. Поиск проверяется по своему PAN/ID, экспорт — по своему merchantId. Для Cards проверяются совпадающие и несовпадающие фильтры. Положительный асинхронный эффект ждем до 15 секунд с опросом 500 мс, это ограничивает длительность ожидания. При отказе брокера отсутствие записи и восстановленный баланс наблюдаются непрерывно 4 секунды в пределах 15 секунд.

## 6. Доказательства работоспособности проверок

| Тест | Намеренное изменение | Результат |
|---|---|---|
| LoggerContractTest.sameIdDeduplicatesButDifferentBusinessDataConflicts | Отключена дедупликация повторного сообщения | Тест упал: ожидался HTTP 200, получен HTTP 201 |
| LoggerContractTest.conflictingReplayIdentifiesLogger | Текст ошибки конфликта заменен с Transaction conflict на Unexpected conflict | Тест упал на проверке текста ошибки: ожидался Transaction conflict, получен Unexpected conflict |
| SearchContractTest.exportsOnlySelectedMerchant | Имя экспортируемого файла заменено с transactions.csv на wrong.csv | Тест упал на проверке Content-Disposition: ожидалось имя transactions.csv, получено wrong.csv |

Все три намеренных изменения обнаружены проверками. Примеры подтверждены сохраненными отчетами Surefire от проверки мутаций, выполненной до последних доработок тестов.
Локальные файлы подтверждения (пути от корня репозитория):

- `services/api-tests/target/practice-5/practice5-694ddc9b256b/mutations/com.processing.api.LoggerContractTest.txt` — падения проверок дедупликации и текста конфликта.
- `services/api-tests/target/practice-5/practice5-694ddc9b256b/mutations/com.processing.api.SearchContractTest.txt` — падение проверки имени CSV-файла.

